package scala.meta.internal.metals

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.{meta => m}

import scala.meta._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.newScalaFile.NewFileTemplate
import scala.meta.internal.parsing.Trees
import scala.meta.internal.pc.Identifier
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.XtensionSemanticdbSymbolInformation
import scala.meta.internal.{semanticdb => s}
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ResourceOperation
import org.eclipse.lsp4j.TextDocumentEdit
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.{Either => JEither}

class PackageProvider(
    buildTargets: BuildTargets,
    trees: Trees,
    refProvider: ReferenceProvider,
    buffers: Buffers,
    defProvider: DefinitionProvider,
) {
  import PackageProvider._

  def workspaceEdit(
      path: AbsolutePath,
      fileContent: String,
      documentVersion: Option[Int],
  ): Option[WorkspaceEdit] =
    packageStatement(path, fileContent).map { template =>
      workspaceEdit(
        path,
        template.fileContent,
        documentVersion = documentVersion,
      )
    }

  def packageStatement(
      path: AbsolutePath,
      fileContent: String = "",
  ): Option[NewFileTemplate] = {

    def packageObjectStatement(
        packageParts: List[String]
    ): Option[NewFileTemplate] = {
      val packageDeclaration =
        if (packageParts.size > 1)
          s"package ${packageParts.dropRight(1).map(p => wrap(p)).mkString(".")}\n\n"
        else ""
      packageParts.lastOption.map { packageObjectName =>
        val indent = "  "
        val backtickedName = wrap(packageObjectName)
        NewFileTemplate(
          s"""|${packageDeclaration}package object $backtickedName {
              |${indent}@@
              |}
              |""".stripMargin
        )
      }
    }

    for {
      packageParts <- Option
        .when(
          path.isScalaOrJava && !path.isJarFileSystem &&
            !path.isScalaScript && !path.isWorksheet && path.toFile
              .length() == 0 && fileContent
              .isEmpty()
        )(deducePackageParts(path))
        .flatten
      if packageParts.size > 0
      newFileTemplate <-
        if (path.filename == "package.scala")
          packageObjectStatement(packageParts)
        else {
          val packageName = packageParts.mkString(".")
          val text =
            if (path.isScala) s"package $packageName\n\n@@"
            else s"package $packageName;\n\n@@"
          Some(NewFileTemplate(text))
        }
    } yield newFileTemplate
  }

  def willMovePath(
      oldPath: AbsolutePath,
      newPath: AbsolutePath,
  )(implicit ec: ExecutionContext): Future[WorkspaceEdit] = {
    val edit = AbsoluteDir.from(oldPath) match {
      case Some(oldDir) =>
        Some(willMoveDir(oldDir, AbsoluteDir(newPath)))
      case None =>
        AbsoluteFile
          .from(oldPath)
          .map(willMoveFile(_, AbsoluteFile.from(newPath, oldPath.filename)))
    }
    edit.getOrElse(Future.successful(new WorkspaceEdit()))
  }

  private def willMoveDir(
      oldDirPath: AbsoluteDir,
      newDirPath: AbsoluteDir,
  )(implicit ec: ExecutionContext): Future[WorkspaceEdit] = {
    val filesWithTrees = oldDirPath.listRecursive
      .filter(_.isScalaFilename)
      .flatMap(oldFilePath =>
        AbsoluteFile.from(oldFilePath).map { oldFile =>
          oldFile -> AbsoluteFile(newDirPath.resolve(oldFile, oldDirPath))
        }
      )
      .flatMap { case rename @ (oldPath, newPath) =>
        for {
          oldPkgParts <- deducePackageParts(oldPath.value)
          newPkgParts <- deducePackageParts(newPath.value)
          tree <- trees.get(oldPath.value)
        } yield (rename, oldPkgParts, newPkgParts, tree)
      }
      .toList

    val changesInMovedFiles = Future
      .traverse(filesWithTrees) {
        case ((oldFilePath, _), oldPkgParts, newPkgParts, tree) =>
          Future {
            calcMovedFileEdits(
              tree,
              oldFilePath,
              oldPkgParts,
              newPkgParts,
            ).getOrElse(new WorkspaceEdit())
          }
      }
      .map(_.mergeChanges)

    val shortestPkg = filesWithTrees.minByOption { case (_, oldPkg, _, _) =>
      oldPkg.size
    }

    val changesInReferences = shortestPkg.map {
      case (_, oldPkgParts, newPkgParts, _) =>
        calcRefsEditsForDirMove(
          oldPkgParts,
          newPkgParts,
          filesWithTrees.map { case ((oldFilePath, _), _, _, _) => oldFilePath },
        )
    }
    Future
      .sequence(List(changesInMovedFiles) ++ changesInReferences)
      .map(_.mergeChanges)
  }

  private def calcRefsEditsForDirMove(
      oldPackageParts: List[String],
      newPackageParts: List[String],
      files: List[AbsoluteFile],
  )(implicit ec: ExecutionContext): Future[WorkspaceEdit] = Future {
    def isOneOfMovedFiles(uri: String) =
      files.exists(_.value.toURI.toString() == uri)
    val references =
      files.flatMap { path =>
        for {
          decl <- findTopLevelDecls(path, oldPackageParts)
          reference <- findReferences(decl, path)
        } yield reference
      }

    calcRefsEdits(
      oldPackageParts,
      newPackageParts,
      references,
      !isOneOfMovedFiles(_),
    )
  }

  private def willMoveFile(oldPath: AbsoluteFile, newPath: AbsoluteFile)(
      implicit ec: ExecutionContext
  ): Future[WorkspaceEdit] = Future {
    val edit =
      for {
        tree <- trees.get(oldPath.value)
        oldPackageParts <- deducePackageParts(oldPath.value)
        newPackageParts <- deducePackageParts(newPath.value)
        fileEdits <- calcMovedFileEdits(
          tree,
          oldPath,
          oldPackageParts.toList,
          newPackageParts.toList,
        )
      } yield {
        val oldUri = oldPath.stringUri
        val references =
          findTopLevelDecls(oldPath, oldPackageParts).flatMap(
            findReferences(_, oldPath)
          )
        val refsEdits =
          calcRefsEdits(
            oldPackageParts,
            newPackageParts,
            references,
            _ != oldUri,
          )
        List(fileEdits, refsEdits).mergeChanges
      }
    edit.getOrElse(new WorkspaceEdit())
  }

  private def calcMovedFileEdits(
      tree: Tree,
      oldPath: AbsoluteFile,
      expectedOldPackageParts: List[String],
      newPackageParts: List[String],
  ): Option[WorkspaceEdit] = {
    val pkgStructure @ PackagesStructure(pkgs, optObj) = findPackages(tree)
    val oldPackagesParts = pkgStructure.allParts()

    def oldPackagesMatchOldPath = oldPackagesParts == expectedOldPackageParts
    def oldPackagesMatchNewPath = oldPackagesParts == newPackageParts
    def oldPackagesWithoutObjectMatchOldPath =
      oldPackagesParts.dropRight(1) == expectedOldPackageParts

    optObj match {
      case None if oldPackagesMatchOldPath && !oldPackagesMatchNewPath =>
        calcPackageEdit(pkgs, newPackageParts, oldPath)
      case Some(obj) if oldPackagesMatchOldPath && !oldPackagesMatchNewPath =>
        for {
          lastPart <- newPackageParts.lastOption
          edits <- calcPackageEdit(pkgs, newPackageParts.dropRight(1), oldPath)
          objectEdit = workspaceEdit(
            oldPath.value,
            lastPart,
            obj.name.pos.toLsp,
          )
        } yield {
          if (oldPackagesParts == newPackageParts.dropRight(1)) objectEdit
          else List(edits, objectEdit).mergeChanges
        }
      case Some(_)
          if !oldPackagesMatchNewPath && oldPackagesWithoutObjectMatchOldPath =>
        calcPackageEdit(pkgs, newPackageParts, oldPath)
      case _ => None
    }
  }

  private def calcRefsEdits(
      oldPackageParts: List[String],
      newPackageParts: List[String],
      references: List[Reference],
      fileFilter: String => Boolean,
  ): WorkspaceEdit =
    references
      .groupBy(_.uri)
      .collect {
        case (uri, references) if fileFilter(uri) =>
          calcRefsEditsForFile(
            oldPackageParts,
            newPackageParts,
            references,
            uri,
          )
      }
      .toSeq
      .mergeChanges

  private def calcRefsEditsForFile(
      oldPackageParts: List[String],
      newPackageParts: List[String],
      references: List[Reference],
      fileUri: String,
  ): WorkspaceEdit = {
    val filePath = fileUri.toAbsolutePath
    val optTree = trees.get(filePath)

    val importersAndParts = optTree
      .fold(Vector.empty[Importer])(findImporters)
      .map(i => (i, extractNames(i.ref)))

    val baseRename = PackagePartsRenamer(oldPackageParts, newPackageParts)
    val packagesRenames = optTree
      .map(collectPackageRenames(baseRename, _))
      .getOrElse(List(baseRename))
    val shortestRename = packagesRenames.last.newPackageParts.mkString(".")

    val importerRenamer =
      new ImporterRenamer(references.toList, packagesRenames)

    val ImportEdits(handledByFullyQualified, fullyQuilifiedChanges) =
      calcFullyQuilifiedEdits(references.toList, filePath, packagesRenames)

    val ImportEdits(imported, refsInImportsEdits) =
      calcAllImportsEditsForFileMove(
        importersAndParts,
        filePath,
        importerRenamer,
      )
    val toImport = (references.toSet -- handledByFullyQualified)
      .map(_.definition)
      .toSet -- imported

    val newImports = Option.when(toImport.nonEmpty) {
      calcNewImports(optTree, toImport, shortestRename)
    }
    val changes =
      refsInImportsEdits ++ newImports ++ fullyQuilifiedChanges
    new WorkspaceEdit(
      Map(filePath.toURI.toString -> changes.toList.asJava).asJava
    )
  }

  private def collectPackageRenames(
      rename: PackagePartsRenamer,
      fileTree: Tree,
  ): List[PackagePartsRenamer] = {
    def collectPackageRenames(
        lastRename: PackagePartsRenamer,
        filePackageParts: List[List[String]],
        acc: List[PackagePartsRenamer] = List.empty,
        adjustNew: Boolean = true,
    ): List[PackagePartsRenamer] =
      filePackageParts match {
        case currentParts :: rest
            if (lastRename.oldPackageParts.startsWith(currentParts)) =>
          if (adjustNew && lastRename.newPackageParts.startsWith(currentParts))
            collectPackageRenames(
              lastRename.dropFromBoth(currentParts.length),
              rest,
              lastRename :: acc,
            )
          else
            collectPackageRenames(
              lastRename.dropFromOld(currentParts.length),
              rest,
              lastRename :: acc,
              adjustNew = false,
            )
        case _ => lastRename :: acc
      }

    val filePackages = findPackages(fileTree)
    val isNewPackageTheSameAsFilePackage =
      filePackages.allParts() == rename.newPackageParts
    val baseRename =
      if (isNewPackageTheSameAsFilePackage)
        rename.copy(newPackageParts = List.empty)
      else rename
    collectPackageRenames(
      baseRename,
      filePackages.allPackagesParts(),
      adjustNew = !isNewPackageTheSameAsFilePackage,
    ).reverse
  }

  /**
   * Calculates edits for selects (fully and partially quilified).
   * E.g
   * Moved file:
   * file://root/a/b/c/d/File.scala
   * ```
   * package a.b.c.d
   * object A {
   *   case class C()
   * }
   * case class B()
   * ```
   * Calculates edits for the following imports:
   * ```
   * package a.b
   *
   * import c.d.A  //NO
   * import a.b.c.d.{A, B => BB} //NO
   * import a.b.c.d._ //NO
   * import c.d.A.C //YES
   * import c //NO this is not handled at all
   *
   * c.d.B //YES
   * c.B //PARTIALLY will rename to B (will be imported later as c.d.B)
   * ```
   * @return
   *   - edits for handled imports
   *   - references that are imported after changes
   */
  private def calcFullyQuilifiedEdits(
      refs: List[Reference],
      source: AbsolutePath,
      pkgRenames: List[PackagePartsRenamer],
  ): ImportEdits[Reference] = {
    def collectPartsFromSelect(select: m.Term.Select): Option[List[String]] =
      select match {
        case Term.Select(s: Term.Select, name) =>
          collectPartsFromSelect(s).map(_ :+ name.value)
        case Term.Select(s: Term.Name, name) => Some(List(s.value, name.value))
        case _ => None
      }

    def findPackageRename(ref: Reference, parts: List[String]): Option[String] =
      pkgRenames.collectFirst {
        case PackagePartsRenamer(oldPackageParts, newPackageParts)
            if parts.startsWith(ref.allParts(oldPackageParts)) =>
          (newPackageParts ++ parts.drop(oldPackageParts.length)).mkString(".")
      }

    def existsPackagePart(ref: Reference, parts: List[String]): Boolean =
      pkgRenames.exists { case PackagePartsRenamer(oldPackageParts, _) =>
        val allParts = ref.allParts(oldPackageParts)
        allParts.tails.toList.dropRight(2).exists(parts.startsWith(_))
      }

    val refsWithEdits = for {
      ref <- refs
      select <- trees
        .findLastEnclosingAt[m.Term.Select](source, ref.pos.getStart())
        .toList
      selectParts <- collectPartsFromSelect(select).toList
    } yield findPackageRename(ref, selectParts)
      .map(newFullyQulifiedSymbol =>
        (List(ref), new TextEdit(select.pos.toLsp, newFullyQulifiedSymbol))
      )
      .orElse {
        if (existsPackagePart(ref, selectParts))
          Some((List(), new TextEdit(select.pos.toLsp, ref.fullName)))
        else None
      }
    val (handled, edits) = refsWithEdits.flatten.unzip
    ImportEdits(handled.flatten, edits)
  }

  /**
   * Calculates edits for import statemants that select elements from files old package.
   * E.g
   * Moved file:
   * file://root/a/b/c/d/File.scala
   * ```
   * package a.b.c.d
   * object A {
   *   case class C()
   * }
   * case class B()
   * ```
   * Calculates edits for the following imports:
   * ```
   * package a.b
   *
   * import c.d.A  //YES
   * import a.b.c.d.{A, B => BB} //YES
   * import a.b.c.d._ //PARTIALLY if possible will delete this import (all elements imported by _ will be added explicitly)
   * import c.d.A.C //NO
   * ```
   * @return
   *   - edits for handled imports
   *   - references that are imported after changes
   */
  private def calcAllImportsEditsForFileMove(
      importersAndParts: Vector[(Importer, List[String])],
      source: AbsolutePath,
      importerRenamer: ImporterRenamer,
  ): ImportEdits[TopLevelDeclaration] = {
    lazy val directlyImportedSymbols =
      importersAndParts
        .withFilter { case (_, parts) =>
          importerRenamer.renames.exists(rename =>
            parts.startsWith(rename.oldPackageParts)
          )
        }
        .flatMap(_._1.importees)
        .collect {
          case Importee.Name(name) => name
          case Importee.Rename(from, _) => from
          case Importee.Unimport(name) => name
        }
        .flatMap(name =>
          defProvider
            .symbolOccurrence(source, name.pos.toLsp.getStart())
            .map(_._1.symbol)
        )
        .toSet

    val importEdits =
      importersAndParts.map { case (importer, importParts) =>
        calcImportEditsForFileMove(
          importer,
          importParts,
          source,
          () => directlyImportedSymbols,
          importerRenamer,
        )
      }
    ImportEdits(
      importEdits.flatMap(_.handledRefs).toList,
      importEdits.flatMap(_.edits).toList,
    )
  }

  private def calcImportEditsForFileMove(
      importer: Importer,
      importParts: List[String],
      source: AbsolutePath,
      directlyImportedSymbols: () => Set[String],
      importerRenamer: ImporterRenamer,
  ): ImportEdits[TopLevelDeclaration] = {
    scribe.info(s"calcImportEditsForFileMove: Starting with importer=${importer.syntax}, importParts=${importParts}, source=${source}")
    val renameResult = importerRenamer.renameFor(importParts)
    scribe.info(s"calcImportEditsForFileMove: renameFor(${importParts}) returned: ${renameResult}")
    val finalResult = renameResult
      .map { case (newPackageName, referencesNames) =>
        scribe.info(s"calcImportEditsForFileMove: Processing rename - newPackageName=${newPackageName}, referencesNames=${referencesNames}")
        lazy val wildcardImportsOnlyFromMovedFiles = {
          val symbolsImportedByWildcard = defProvider
            .symbolOccurrence(source, importer.ref.pos.toLsp.getEnd)
            .map { case (so, _) => so.symbol }
            .toList
            .flatMap(symbol =>
              refProvider
                .referencesForWildcardImport(
                  symbol,
                  source,
                  directlyImportedSymbols(),
                )
            )
          val result = symbolsImportedByWildcard.forall(symbol =>
            referencesNames.exists(_.symbols.contains(symbol))
          )
          scribe.info(s"calcImportEditsForFileMove: wildcardImportsOnlyFromMovedFiles=${result}, symbolsImportedByWildcard.size=${symbolsImportedByWildcard}")
          result
        }
        lazy val importedImplicits =
          referencesNames.filter(_.isGivenOrExtension).toList
        val importees = importer.importees
        scribe.info(s"calcImportEditsForFileMove: Processing ${importees.size} importees: ${importees.map(_.syntax)}")
        def findReferenceByName(
            name: String
        ): Option[List[TopLevelDeclaration]] =
          referencesNames.find(_.name == name).map(List(_))
        def findReference(
            importee: Importee
        ): Option[List[TopLevelDeclaration]] = {
          val result = importee match {
            case Importee.Name(name) => 
              val found = findReferenceByName(name.value)
              scribe.info(s"calcImportEditsForFileMove: Importee.Name(${name.value}) -> ${found.isDefined}")
              found
            case Importee.Rename(from, _) => 
              val found = findReferenceByName(from.value)
              scribe.info(s"calcImportEditsForFileMove: Importee.Rename(${from.value}) -> ${found.isDefined}")
              found
            case Importee.Unimport(name) => 
              val found = findReferenceByName(name.value)
              scribe.info(s"calcImportEditsForFileMove: Importee.Unimport(${name.value}) -> ${found.isDefined}")
              found
            case Importee.Wildcard() if wildcardImportsOnlyFromMovedFiles =>
              scribe.info(s"calcImportEditsForFileMove: Importee.Wildcard() -> updating wildcard import path")
              Some(referencesNames.toList)
            case Importee.GivenAll() if wildcardImportsOnlyFromMovedFiles =>
              scribe.info(s"calcImportEditsForFileMove: Importee.GivenAll() -> handling as importedImplicits (${importedImplicits.size} items)")
              Some(importedImplicits.toList)
            case other => 
              scribe.info(s"calcImportEditsForFileMove: Unhandled importee: ${other.syntax} -> None")
              None
          }
          result
        }
        val (handledRefs0, refsImportees, refsIndices) =
          importees.zipWithIndex.flatMap { case (imp, indx) =>
            findReference(imp).map((_, imp, indx))
          }.unzip3
        val handledRefs = handledRefs0.flatten
        scribe.info(s"calcImportEditsForFileMove: Found ${refsImportees} matching importees, ${handledRefs} handled references")
        if (refsImportees.length == 0) {
          scribe.info(s"calcImportEditsForFileMove: No matching importees found, returning empty ImportEdits")
          ImportEdits.empty
        } else {
          val importeeStrings = refsImportees.flatMap {
            case Importee.Name(name) => Some(name.value)
            case Importee.Rename(from, to) =>
              Some(s"${from.value} => ${to.value}")
            case Importee.GivenAll() => Some("given")
            case _ => None
          }
          val newImportStr = {
            val hasWildcardToUpdate = refsImportees.exists {
              case _: Importee.Wildcard => true
              case _ => false
            } && wildcardImportsOnlyFromMovedFiles

            if (hasWildcardToUpdate && newPackageName.nonEmpty) {
              s"${newPackageName.mkString(".")}._"
            } else {
              def bracedImporteeStr = refsImportees.filter {
                case _: Importee.Wildcard => false
                case _: Importee.Unimport => false
                case _ => true
              } match {
                case List(Importee.Rename(_, _)) =>
                  s"{${mkImportees(importeeStrings)}}"
                case _ => mkImportees(importeeStrings)
              }
              if (importeeStrings.nonEmpty && newPackageName.nonEmpty)
                s"${newPackageName.mkString(".")}.$bracedImporteeStr"
              else ""
            }
          }
          scribe.info(s"calcImportEditsForFileMove: Generated newImportStr='${newImportStr}', importeeStrings=${importeeStrings}")
          if (refsImportees.length == importer.importees.length) {
            scribe.info(s"calcImportEditsForFileMove: All importees matched (${refsImportees}/${importer.importees}), handling complete import replacement")
            val importChange =
              if (newImportStr == "") {
                // we delete the import
                scribe.info(s"calcImportEditsForFileMove: Deleting entire import statement")
                importer.parent
                  .map(line =>
                    new TextEdit(
                      extendPositionToIncludeNewLine(line.pos).toLsp,
                      "",
                    )
                  )
                  .toList
              } else {
                scribe.info(s"calcImportEditsForFileMove: Replacing entire import with: ${newImportStr}")
                List(new TextEdit(importer.pos.toLsp, newImportStr))
              }
            ImportEdits(handledRefs, importChange)
          } else {
            // In this branch some importees should be removed from the import, but not all.
            // Trying to remove them with commas
            scribe.info(s"calcImportEditsForFileMove: Partial import handling - removing ${refsImportees.length}/${importer.importees.length} importees")
            val indSet = refsIndices.toSet
            val oldImportEdits =
              importees.zip(importees.drop(1)).zipWithIndex.flatMap {
                case ((i1, i2), ind) =>
                  val pos =
                    if (indSet.contains(ind))
                      Some(
                        i1.pos.toLsp.copy(
                          endLine = i2.pos.startLine,
                          endCharacter = i2.pos.startColumn,
                        )
                      )
                    else if (
                      indSet.contains(ind + 1) && importees.length == ind + 2
                    ) // cases like "import foo.{A, B, C}" when C is moved
                      Some(
                        i2.pos.toLsp.copy(
                          startLine = i1.pos.endLine,
                          startCharacter = i1.pos.endColumn,
                        )
                      )
                    else None
                  pos.map(new TextEdit(_, ""))
              }
            val newImportPos = posNextToEndOf(importer.pos)
            val importChages =
              if (newImportStr == "") oldImportEdits
              else {
                val newImportEdit =
                  new TextEdit(newImportPos, s"import $newImportStr\n")
                newImportEdit :: oldImportEdits
              }
            scribe.info(s"calcImportEditsForFileMove: Partial import result - ${importChages} edits")
            ImportEdits(handledRefs, importChages)
          }
        }
      }
      .getOrElse {
        scribe.info(s"calcImportEditsForFileMove: No rename found for importParts=${importParts}, returning empty ImportEdits")
        ImportEdits.empty
      }
    scribe.info(s"calcImportEditsForFileMove: Final result - ${finalResult.handledRefs.size} handled refs, ${finalResult.edits} edits")
    finalResult
  }

  private def findImporters(tree: Tree): Vector[Importer] = {
    def go(tree: Tree): LazyList[Importer] = tree match {
      case i: Import => LazyList.from(i.importers)
      case t => LazyList.from(t.children).flatMap(go)
    }
    go(tree).toVector
  }

  private def calcNewImports(
      optTree: Option[Tree],
      referencesNames: Set[TopLevelDeclaration],
      newPackageName: String,
  ): TextEdit = {
    def importString = {
      val toImport =
        if (newPackageName.isEmpty)
          referencesNames.filter(_.innerPackageParts.isEmpty)
        else referencesNames
      val (toImportGivens, toImportNotGivens) =
        toImport.partition(_.isGivenOrExtension)
      val allToImport =
        (toImportNotGivens.map(decl =>
          (decl.innerPackageParts, decl.name)
        ) ++ toImportGivens.map(decl => (decl.innerPackageParts, "given")))
          .groupMap(_._1)(_._2)
          .map { case (innerPackageParts, names) =>
            (innerPackageParts :+ mkImportees(names.toList.sorted))
              .mkString(".")
          }

      if (newPackageName.isEmpty)
        allToImport.map(sym => s"import $sym\n").mkString
      else allToImport.map(sym => s"import $newPackageName.$sym\n").mkString
    }

    val optLastPackage = for {
      tree <- optTree
      lastPackage <- findPackages(tree).pkgs.lastOption
    } yield lastPackage.pkg

    val optLastImport = for {
      lastPackage <- optLastPackage
      lastImport <- lastPackage.stats.collect { case i: Import => i }.lastOption
    } yield lastImport

    val optNewImportRange = optLastImport
      .map(_.pos)
      .orElse(optLastPackage.map(_.ref.pos))
      .map(posNextToEndOf(_))

    val range = optNewImportRange.getOrElse(
      new Range(new Position(0, 0), new Position(0, 0))
    )
    new TextEdit(range, importString)
  }

  private def findTopLevelDecls(
      path: AbsoluteFile,
      topLevelPackageParts: List[String],
  ): List[TopLevelDeclaration] = {
    val filename = path.filename
    val filenamePart = filename.stripSuffix(".scala").stripSuffix(".sc")

    def isPackageObjectLike(symbol: String) =
      Set("package", filenamePart ++ "$package").contains(symbol)
    def isTopLevelPackageOrPackageObject(symbol: String): Boolean =
      symbol.isPackage || {
        val (desc, owner) = DescriptorParser(symbol)
        (isPackageObjectLike(desc.name.value)
        && isTopLevelPackageOrPackageObject(owner))
      }

    def alternatives(si: s.SymbolInformation): Set[String] =
      if (si.isCase) Set(s"${si.symbol.dropRight(1)}.") else Set.empty

    val dialect = {
      val optDialect =
        for {
          buidTargetId <- buildTargets.inverseSources(path.value)
          scalaTarget <- buildTargets.scalaTarget(buidTargetId)
        } yield ScalaVersions.dialectForScalaVersion(
          scalaTarget.scalaVersion,
          includeSource3 = true,
        )
      optDialect.getOrElse(dialects.Scala213)
    }
    m.internal.mtags.Mtags
      .index(path.value, dialect)
      .symbols
      .flatMap { si =>
        if (si.symbol.isPackage)
          None
        else {
          val (desc, owner) = DescriptorParser(si.symbol)
          val descName = desc.name.value
          if (
            isTopLevelPackageOrPackageObject(owner) && !isPackageObjectLike(
              descName
            )
          ) {
            val packageParts =
              owner
                .split('/')
                .filter {
                  case "" => false
                  case ObjectSymbol(obj) => !isPackageObjectLike(obj)
                  case _ => true
                }
                .drop(topLevelPackageParts.length)
                .toList
            Some(
              TopLevelDeclaration(
                descName,
                packageParts,
                si.isGiven || si.isExtension,
                alternatives(si) + si.symbol,
              )
            )
          } else None
        }
      }
      .groupBy(decl => (decl.name, decl.innerPackageParts))
      .collect { case ((name, innerPackageParts), otherDecl) =>
        TopLevelDeclaration(
          name,
          innerPackageParts,
          otherDecl.map(_.isGivenOrExtension).reduce(_ || _),
          otherDecl.flatMap(_.symbols).toSet,
        )
      }
      .toList
  }

  private def extendPositionToIncludeNewLine(pos: inputs.Position) = {
    if (pos.start > 0 && pos.input.chars(pos.start - 1) == '\n')
      inputs.Position.Range(pos.input, pos.start - 1, pos.end)
    else pos
  }

  private def findReferences(
      decl: TopLevelDeclaration,
      path: AbsoluteFile,
  ): List[Reference] = {
    refProvider
      .workspaceReferences(
        path.value,
        decl.symbols,
        isIncludeDeclaration = false,
        sourceContainsDefinition = true,
      )
      .map { loc =>
        Reference(decl, loc.getRange(), loc.getUri())
      }
      .toList
  }

  private def findPackages(tree: Tree): PackagesStructure = {
    @tailrec
    def extractOuterPackage(
        tree: Tree,
        acc: PackagesStructure = PackagesStructure.empty,
    ): PackagesStructure =
      tree match {
        case p @ Pkg(_, Nil) => acc.addPackage(p)
        case p @ Pkg(_, st :: _) => extractOuterPackage(st, acc.addPackage(p))
        case p: Pkg.Object => acc.withObject(p)
        case _ => acc
      }
    tree match {
      case Source(List(pk: Pkg)) => extractOuterPackage(pk).reverse
      case _ => PackagesStructure.empty
    }
  }

  private def wrap(str: String): String = Identifier.backtickWrap(str)

  private def workspaceEdit(
      path: AbsolutePath,
      replacement: String,
      range: Range = new Range(new Position(0, 0), new Position(0, 0)),
      documentVersion: Option[Int] = None,
  ): WorkspaceEdit = {
    documentVersion match {
      case None =>
        val textEdit = new TextEdit(range, replacement)
        val textEdits = List(textEdit).asJava
        val changes = Map(path.toURI.toString -> textEdits).asJava
        new WorkspaceEdit(changes)
      case Some(version) =>
        val textEdit = new TextEdit(range, replacement)
        val id =
          new VersionedTextDocumentIdentifier(path.toURI.toString, version)
        val textDocEdit = new TextDocumentEdit(id, List(textEdit).asJava)
        val changes = List(
          JEither.forLeft[TextDocumentEdit, ResourceOperation](textDocEdit)
        ).asJava
        new WorkspaceEdit(changes)
    }
  }

  private def deducePackageParts(path: AbsolutePath): Option[List[String]] = {
    def basePackage = buildTargets
      .inverseSources(path)
      .flatMap(deduceBuildTargetBasePackage(_, _ != path))
      .getOrElse(Nil)
    deducePackagePartsFromPath(path).map(basePackage ++ _)
  }

  private def deducePackagePartsFromPath(
      path: AbsolutePath
  ): Option[List[String]] =
    calcPathToSourceRoot(path).map(_.dropRight(1))

  /**
   * Infer any implicit package prefix for a build target.
   *
   * This is to help with the case where packages in a build target all start
   * with some common package prefix that is not reflected in the directory
   * structure.
   */
  private def deduceBuildTargetBasePackage(
      buildTargetId: BuildTargetIdentifier,
      pathShouldBeSampled: AbsolutePath => Boolean,
  ): Option[List[String]] = {

    /**
     * If a sequence ends in a given suffix, return the sequence without that
     * suffix
     *
     * @param original original sequence
     * @param maybeSuffix suffix to remove from that sequence
     */
    def stripSuffix[A](
        original: List[A],
        maybeSuffix: List[A],
    ): Option[List[A]] = {
      @tailrec
      def loop(
          originalRev: List[A],
          maybeSuffixRev: List[A],
      ): Option[List[A]] = {
        maybeSuffixRev match {
          case Nil => Some(originalRev.reverse)
          case lastSuffix :: maybeRestSuffixRev =>
            originalRev match {
              case `lastSuffix` :: maybeRestOriginalRev =>
                loop(maybeRestOriginalRev, maybeRestSuffixRev)
              case _ => None
            }
        }
      }

      loop(original.reverse, maybeSuffix.reverse)
    }

    // Pull out an arbitrary source file from the build target to infering the base package
    val sampleSourcePathAndTree = buildTargets
      .buildTargetSources(buildTargetId)
      .iterator
      .filter(pathShouldBeSampled)
      .flatMap(path => trees.get(path).map(path -> _))
      .headOption

    for {
      (sampleSourcePath, tree) <- sampleSourcePathAndTree
      packagePartsFromTree = findPackages(tree).allParts
      packagePartsFromPath <- deducePackagePartsFromPath(sampleSourcePath)
      packagePrefix <- stripSuffix(packagePartsFromTree, packagePartsFromPath)
    } yield packagePrefix
  }

  private def calcPathToSourceRoot(
      path: AbsolutePath
  ): Option[List[String]] =
    buildTargets
      .inverseSourceItem(path)
      .map(path.toRelative(_).toNIO)
      .map { _.iterator().asScala.map(p => wrap(p.toString())).toList }

  private def calcPackageEdit(
      oldPackages: List[PackageWithName],
      newPackages: List[String],
      oldPath: AbsoluteFile,
  ): Option[WorkspaceEdit] = {
    val edits = findPackageEdits(newPackages, oldPackages)
    if (edits.isEmpty) None
    else
      for {
        source <- buffers.get(oldPath.value).orElse(oldPath.content())
      } yield pkgEditsToWorkspaceEdit(edits, source.toCharArray(), oldPath)
  }

  /**
   * Splits package declaration into statements
   * Desired property - not to break old visibility regions
   */
  private def findPackageEdits(
      newPackages: List[String],
      oldPackage: List[PackageWithName],
  ): List[PackageEdit] =
    oldPackage match {
      case Nil => Nil
      case PackageWithName(oldPackage, _) :: Nil =>
        List(PackageEdit(oldPackage, newPackages))
      case PackageWithName(oldPackage, oldPackageName) :: nextOld =>
        newPackages.zipWithIndex
          .filter { case (s, _) =>
            oldPackageName.lastOption.contains(s)
          }
          .minByOption { case (_, i) =>
            (i - oldPackageName.length).abs
          } match {
          case None =>
            PackageEdit(oldPackage, List()) :: findPackageEdits(
              newPackages,
              nextOld,
            )
          case Some((_, i)) =>
            val (currParts, nextNew) = newPackages.splitAt(i + 1)
            PackageEdit(oldPackage, currParts) ::
              findPackageEdits(
                nextNew,
                nextOld,
              )
        }
    }

  private def pkgEditsToWorkspaceEdit(
      edits: List[PackageEdit],
      source: Array[Char],
      oldPath: AbsoluteFile,
  ): WorkspaceEdit = {
    val extend: (Int, Int) => (Int, Int) =
      extendRangeToIncludeWhiteCharsAndTheFollowingNewLine(source, List(':'))
    edits.flatMap {
      // delete package declaration
      case PackageEdit(pkg, Nil) =>
        val topEditRange @ (rangeStart, rangeEnd) =
          extend(pkg.pos.start, pkg.ref.pos.end)
        val editRanges =
          if (rangeEnd + 1 < source.length && source(rangeEnd + 1) == '{') {
            List(
              extend(rangeStart, rangeEnd + 1),
              extend(pkg.pos.end - 1, pkg.pos.end),
            )
          } else List(topEditRange)
        editRanges.map { case (startOffset, endOffset) =>
          workspaceEdit(
            oldPath.value,
            "",
            new m.Position.Range(pkg.pos.input, startOffset, endOffset).toLsp,
          )
        }
      // rename package
      case PackageEdit(pkg, newParts) =>
        val edit =
          workspaceEdit(
            oldPath.value,
            newParts.mkString("."),
            pkg.ref.pos.toLsp,
          )
        List(edit)
    }.mergeChanges
  }

  private def mkImportees(names: List[String]): String =
    names match {
      case List(single) => single
      case list => list.mkString("{", ", ", "}")
    }

  private def posNextToEndOf(pos: m.Position): Range = {
    var start = pos.end
    while (
      start > 0 && start < pos.input.chars.length && pos.input.chars(
        start
      ) != '\n'
    ) {
      start += 1
    }
    if (start < pos.input.chars.length) start += 1
    m.Position.Range(pos.input, start, start).toLsp
  }

}

object PackageProvider {
  final case class DirChange(oldPackage: List[String], newPackage: List[String])

  @tailrec
  private def extractNames(
      t: Term,
      acc: List[String] = List.empty,
  ): List[String] = {
    t match {
      case n: Term.Name => n.value +: acc
      case s: Term.Select => extractNames(s.qual, s.name.value +: acc)
    }
  }

  case class PackageWithName(pkg: Pkg, name: List[String])

  case class PackagesStructure(
      pkgs: List[PackageWithName],
      pkgObject: Option[Pkg.Object] = None,
  ) {
    def withObject(pkgObject: Pkg.Object): PackagesStructure =
      PackagesStructure(pkgs, Some(pkgObject))
    def addPackage(pkg: Pkg): PackagesStructure =
      PackagesStructure(
        PackageWithName(pkg, extractNames(pkg.ref)) :: pkgs,
        pkgObject,
      )
    def reverse(): PackagesStructure =
      PackagesStructure(pkgs.reverse, pkgObject)
    def allPackagesParts(): List[List[String]] =
      pkgs.map(_.name) ++ pkgObject.map(obj => List(obj.name.value)).toList
    def allParts(): List[String] = allPackagesParts.flatten
  }

  object PackagesStructure {
    def empty: PackagesStructure = PackagesStructure(List(), None)
  }

  case class PackageEdit(
      pkg: Pkg,
      renameToParts: List[String], // empty list means delete
  )
}

case class ImportEdits[+R](handledRefs: List[R], edits: List[TextEdit])

object ImportEdits {
  def empty[R]: ImportEdits[R] = ImportEdits(List.empty, List.empty)
}

/**
 * Top level declaration.
 *
 * @param name
 *   short name of the declaration
 * @param innerPackageParts
 *   inner packages as list of parts
 * @param isGivenOrExtension
 *    if the declaration is an extension or given
 * @param symbols
 *    all the symbols that are represented by `basePackage.innerPackageParts.name`
 *
 * E.g.
 * file://root/a/b/c/File.scala
 * package a.b
 * package c {
 *   package d {
 *     case class O { }
 *   }
 * }
 *
 * TopLevelDeclaration("O", List("d"), false, List("a.b.c.d.O#", "a.b.c.d.O."))
 */
case class TopLevelDeclaration(
    name: String,
    innerPackageParts: List[String],
    isGivenOrExtension: Boolean,
    symbols: Set[String],
) {
  def allParts: List[String] = innerPackageParts :+ name
  def nameWithInnerPackages: String = allParts.mkString(".")
  def dropPackageParts(i: Int): TopLevelDeclaration =
    TopLevelDeclaration(
      name,
      innerPackageParts.drop(i),
      isGivenOrExtension,
      symbols,
    )
}

case class Reference(
    definition: TopLevelDeclaration,
    pos: Range,
    uri: String,
) {
  def fullName: String = definition.nameWithInnerPackages
  def allParts(prefix: List[String]): List[String] =
    prefix ++ definition.allParts
}

/**
 * Rename for an importer.
 *
 * E.g.
 * file://root/a/b/d/e/File.scala
 * ```
 * package a.b
 * package d.e
 *
 * object SomeObject { }
 * ```
 * Gets moved to file://root/a/b/c/File.scala.
 * In file with reference:
 * ```
 * package a.b
 * import a.b.d.e.SomeObject
 *
 * val m = d.e.SomeObject
 * ```
 * We will rename:
 * - a.b.d.e.SomeObject -> a.b.c.SomeObject
 *     which corresponds to
 *     PackagePartsRenamer(List("a", "b", "d", "e"), List("a", "b", "c"))
 * - d.e.SomeObject -> c.SomeObject
 *     which corresponds to
 *     PackagePartsRenamer(List("d", "e"), List("c"))
 */
case class PackagePartsRenamer(
    oldPackageParts: List[String],
    newPackageParts: List[String],
) {
  def dropFromOld(i: Int): PackagePartsRenamer =
    PackagePartsRenamer(oldPackageParts.drop(i), newPackageParts)
  def dropFromBoth(i: Int): PackagePartsRenamer =
    PackagePartsRenamer(oldPackageParts.drop(i), newPackageParts.drop(i))
}

class ImporterRenamer(
    references: List[Reference],
    val renames: List[PackagePartsRenamer],
) {
  lazy val referencesNamesByPackage
      : Map[List[String], Set[TopLevelDeclaration]] =
    references
      .groupMap(_.definition.innerPackageParts)(_.definition)
      .map { case (key, v) =>
        (key, v.toSet)
      }

  def renameFor(
      importParts: List[String]
  ): Option[(List[String], Set[TopLevelDeclaration])] = renames.collectFirst {
    case PackagePartsRenamer(oldParts, newParts)
        if (importParts.startsWith(oldParts) && referencesNamesByPackage
          .contains(importParts.drop(oldParts.length))) =>
      val otherParts = importParts.drop(oldParts.length)
      (newParts ++ otherParts, referencesNamesByPackage(otherParts))
  }
}

object ObjectSymbol {
  def unapply(symbol: String): Option[String] =
    if (symbol.nonEmpty && symbol.last == '.') Some(symbol.dropRight(1))
    else None
}

class PackageObject(filenamePart: String) {
  val packageObjectLike: Set[String] =
    Set("package", filenamePart ++ "$package")
  def isPackageObjectLike(name: String): Boolean =
    packageObjectLike.contains(name)
  def symbols(owner: String): Set[String] = if (owner.isEmpty())
    packageObjectLike
  else packageObjectLike.map(owner ++ _)
}

case class AbsoluteDir(val value: AbsolutePath) extends AnyVal {
  def listRecursive = value.listRecursive
  def resolve(file: AbsoluteFile, relativeTo: AbsoluteDir): AbsolutePath =
    value.resolve(file.value.toRelative(relativeTo.value))
}
object AbsoluteDir {
  def from(path: AbsolutePath): Option[AbsoluteDir] =
    if (path.isDirectory) Some(new AbsoluteDir(path))
    else None
}
case class AbsoluteFile(val value: AbsolutePath) extends AnyVal {
  def filename = value.filename
  def content() = value.readTextOpt
  def stringUri: String = value.toURI.toString()
}

object AbsoluteFile {
  def from(path: AbsolutePath, fileName: String): AbsoluteFile =
    if (path.isDirectory) new AbsoluteFile(path.resolve(fileName))
    else new AbsoluteFile(path)

  def from(path: AbsolutePath): Option[AbsoluteFile] =
    if (path.isFile) Some(new AbsoluteFile(path))
    else None
}
