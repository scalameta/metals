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
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit

class PackageProvider(
    buildTargets: BuildTargets,
    trees: Trees,
    refProvider: ReferenceProvider,
    buffers: Buffers,
) {
  import PackageProvider._
  def willMovePath(
      oldPath: AbsolutePath,
      newPath: AbsolutePath,
  )(implicit ec: ExecutionContext): Future[WorkspaceEdit] =
    if (oldPath.isDirectory) willMoveDir(oldPath, newPath)
    else willMoveFile(oldPath, newPath)

  def workspaceEdit(path: AbsolutePath): Option[WorkspaceEdit] =
    packageStatement(path).map(template =>
      workspaceEdit(path, template.fileContent)
    )

  def packageStatement(path: AbsolutePath): Option[NewFileTemplate] = {

    def packageObjectStatement(
        packageParts: Vector[String]
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
      packageParts <- Option.when(
        path.isScalaOrJava && !path.isJarFileSystem &&
          !path.isScalaScript && path.toFile.length() == 0
      )(deducePkgParts(path))
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

  private def willMoveDir(
      oldDirPath: AbsolutePath,
      newDirPath: AbsolutePath,
  )(implicit ec: ExecutionContext): Future[WorkspaceEdit] = {
    val oldPkg = calcPathToSourceRoot(oldDirPath)
    val newPkg = calcPathToSourceRoot(newDirPath)

    val files = oldDirPath.listRecursive
      .filter(_.isScalaFilename)
      .map(oldFilePath =>
        oldFilePath -> newDirPath.resolve(oldFilePath.toRelative(oldDirPath))
      )
      .toList

    Future
      .traverse(files) { case (oldFilePath, newFilePath) =>
        willMoveFile(
          oldFilePath,
          newFilePath,
          Some(DirChange(oldPkg, newPkg)),
        )
      }
      .map(_.mergeChanges)
  }

  private def willMoveFile(
      oldPath: AbsolutePath,
      newPath: AbsolutePath,
      optDirChange: Option[DirChange] = None,
  )(implicit ec: ExecutionContext): Future[WorkspaceEdit] = Future {
    val edit =
      for {
        tree <- trees.get(oldPath)
        oldPkgParts = deducePkgParts(oldPath)
        newPkgParts = deducePkgParts(newPath)
        fileEdits <- calcMovedFileEdits(
          tree,
          oldPath,
          oldPkgParts.toList,
          newPkgParts.toList,
        )
        refsEdits = calcRefsEdits(
          tree,
          oldPath,
          oldPkgParts,
          newPkgParts,
          optDirChange,
        )
      } yield List(fileEdits, refsEdits).mergeChanges

    edit.getOrElse(new WorkspaceEdit())
  }

  private def calcMovedFileEdits(
      tree: Tree,
      oldPath: AbsolutePath,
      expectedOldPkgParts: List[String],
      newPkgParts: List[String],
  ): Option[WorkspaceEdit] = {
    val pkgStructure @ PkgsStructure(pkgs, optObj) = findPkgs(tree)
    val oldPkgsParts = pkgStructure.allParts()

    def oldPkgsMatchOldPath = oldPkgsParts == expectedOldPkgParts
    def oldPkgsMatchNewPath = oldPkgsParts == newPkgParts
    def oldPkgsWithoutObjectMatchOldPath =
      oldPkgsParts.dropRight(1) == expectedOldPkgParts

    optObj match {
      case None if (oldPkgsMatchOldPath) =>
        calcPkgEdit(pkgs, newPkgParts, oldPath)
      case Some(obj) if (oldPkgsMatchOldPath) =>
        for {
          lastPart <- newPkgParts.lastOption
          edits <- calcPkgEdit(pkgs, newPkgParts.dropRight(1), oldPath)
          objectEdit = workspaceEdit(
            oldPath,
            lastPart,
            obj.name.pos.toLsp,
          )
        } yield List(edits, objectEdit).mergeChanges
      case Some(_)
          if !oldPkgsMatchNewPath && oldPkgsWithoutObjectMatchOldPath =>
        calcPkgEdit(pkgs, newPkgParts, oldPath)
      case _ => None
    }
  }

  private def calcRefsEdits(
      movedTree: Tree,
      oldPath: AbsolutePath,
      oldPkgParts: Vector[String],
      newPkgParts: Vector[String],
      optDirChange: Option[DirChange],
  ): WorkspaceEdit = {
    val newPkgName = newPkgParts.mkString(".")
    val decls = findTopLevelDecls(movedTree)
    val oldUri = oldPath.toURI.toString
    val edits = decls.view
      .flatMap(decl => findRefs(decl, oldUri).map((decl, _)))
      .view
      .groupMap { case (_, location) =>
        location.getUri()
      } { case (decl, _) =>
        decl
      }
      .map { case (fileUri, refs) =>
        val filePath = fileUri.toAbsolutePath
        val refPkgParts = deducePkgParts(fileUri.toAbsolutePath)
        val optTree = trees.get(filePath)
        val refsNames = refs.map(_.value).toSet

        val importersAndParts = optTree
          .fold(Vector.empty[Importer])(findImporters)
          .map(i => (i, extractNames(i.ref)))

        val changes = optDirChange match {
          case Some(DirChange(oldPkg, newPkg)) =>
            val refsInImportsEdits = importersAndParts.flatMap {
              case (importer, importParts) =>
                calcImportEditsForDirMove(importer, importParts, oldPkg, newPkg)
            }
            val shouldAddImports = fileUri != oldUri &&
              !refPkgParts.startsWith(oldPkg) && refsInImportsEdits.isEmpty
            val newImports = Option.when(shouldAddImports)(
              calcNewImports(optTree, refsNames, newPkgName)
            )
            newImports ++ refsInImportsEdits
          case None =>
            val refsInImportsEdits = importersAndParts.flatMap {
              case (importer, importParts) =>
                calcImportEditsForFileMove(
                  importer,
                  importParts,
                  oldPkgParts,
                  refsNames,
                  newPkgName,
                )
            }
            val shouldAddImports =
              fileUri != oldUri && refsInImportsEdits.isEmpty
            val newImports = Option.when(shouldAddImports) {
              calcNewImports(optTree, refsNames, newPkgName)
            }
            refsInImportsEdits ++ newImports
        }

        new WorkspaceEdit(
          Map(filePath.toURI.toString -> changes.toList.asJava).asJava
        )
      }
    edits.toSeq.mergeChanges
  }

  private def calcImportEditsForDirMove(
      importer: Importer,
      importParts: Vector[String],
      oldPkgPrefix: Vector[String],
      newPkgPrefix: Vector[String],
  ): List[TextEdit] = if (importParts.startsWith(oldPkgPrefix)) {
    val newImport = newPkgPrefix ++ importParts.drop(oldPkgPrefix.size)
    List(new TextEdit(importer.ref.pos.toLsp, newImport.mkString(".")))
  } else List.empty

  private def calcNewImports(
      optTree: Option[Tree],
      refsNames: Set[String],
      newPkgName: String,
  ): TextEdit = {
    val importees = mkImportees(refsNames.toList)
    val importString = s"import $newPkgName.$importees\n"

    val optLastPkg = for {
      tree <- optTree
      lastPkg <- findPkgs(tree).pkgs.lastOption
    } yield lastPkg.pkg

    val optLastImport = for {
      lastPkg <- optLastPkg
      lastImport <- lastPkg.stats.collect { case i: Import => i }.lastOption
    } yield lastImport

    val optNewImportRange = optLastImport
      .map(_.pos)
      .orElse(optLastPkg.map(_.ref.pos))
      .map(posNextToEndOf(_))

    val range = optNewImportRange.getOrElse(
      new Range(new Position(0, 0), new Position(0, 0))
    )
    new TextEdit(range, importString)
  }

  private def calcImportEditsForFileMove(
      importer: Importer,
      importParts: Vector[String],
      oldPkgParts: Vector[String],
      refsNames: Set[String],
      newPkgName: String,
  ): List[TextEdit] =
    if (importParts == oldPkgParts) {
      val importees = importer.importees
      def isReferenced(importee: Importee) = importee match {
        case Importee.Name(name) => refsNames.contains(name.value)
        case Importee.Rename(from, _) => refsNames.contains(from.value)
        case Importee.Unimport(name) => refsNames.contains(name.value)
        case _ => false
      }
      val (refsImportees, refsIndices) = importees.zipWithIndex.filter {
        case (i, _) =>
          isReferenced(i)
      }.unzip
      if (refsImportees.length == 0) {
        List.empty
      } else {
        val importeeStr = mkImportees(refsImportees.collect {
          case Importee.Name(name) => name.value
          case Importee.Rename(from, to) =>
            s"${from.value} => ${to.value}"
          case Importee.Unimport(name) => name.value
        })
        val newImportStr = {
          val bracedImporteeStr = refsImportees match {
            case List(Importee.Rename(_, _)) => s"{$importeeStr}"
            case _ => importeeStr
          }
          s"$newPkgName.$bracedImporteeStr"
        }
        if (refsImportees.length == importer.importees.length) {
          List(new TextEdit(importer.pos.toLsp, newImportStr))
        } else {
          // In this branch some importees should be removed from the import, but not all.
          // Trying to remove them with commas
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
          val newImportEdit =
            new TextEdit(newImportPos, s"import $newImportStr\n")
          newImportEdit :: oldImportEdits
        }
      }
    } else List.empty

  private def findImporters(tree: Tree): Vector[Importer] = {
    def go(tree: Tree): LazyList[Importer] = tree match {
      case i: Import => LazyList.from(i.importers)
      case t => LazyList.from(t.children).flatMap(go)
    }
    go(tree).toVector
  }

  private def findTopLevelDecls(tree: Tree): List[Name] = {
    def go(tree: Tree): LazyList[Name] = tree match {
      case d: Defn with Member => LazyList(d.name)
      case t => LazyList.from(t.children).flatMap(go)
    }
    go(tree).toList
  }

  private def findRefs(
      name: Name,
      uri: String,
  ): List[Location] = {
    val params = new ReferenceParams(
      new TextDocumentIdentifier(uri),
      new Position(name.pos.startLine, name.pos.startColumn),
      new ReferenceContext(false), // do not include declaration
    )
    refProvider.references(params).flatMap(_.locations)
  }

  private def findPkgs(tree: Tree): PkgsStructure = {
    @tailrec
    def extractOuterPkg(
        tree: Tree,
        acc: PkgsStructure = PkgsStructure.empty,
    ): PkgsStructure =
      tree match {
        case p @ Pkg(_, Nil) => acc.addPkg(p)
        case p @ Pkg(_, st :: _) => extractOuterPkg(st, acc.addPkg(p))
        case p: Pkg.Object => acc.withObject(p)
        case _ => acc
      }
    tree match {
      case Source(List(pk: Pkg)) => extractOuterPkg(pk).reverse
      case _ => PkgsStructure.empty
    }
  }

  private def wrap(str: String): String = Identifier.backtickWrap(str)

  private def workspaceEdit(
      path: AbsolutePath,
      replacement: String,
      range: Range = new Range(new Position(0, 0), new Position(0, 0)),
  ): WorkspaceEdit = {
    val textEdit = new TextEdit(range, replacement)
    val textEdits = List(textEdit).asJava
    val changes = Map(path.toURI.toString -> textEdits).asJava
    new WorkspaceEdit(changes)
  }

  private def deducePkgParts(path: AbsolutePath): Vector[String] =
    calcPathToSourceRoot(path).dropRight(1)

  private def calcPathToSourceRoot(
      path: AbsolutePath
  ): Vector[String] =
    buildTargets
      .inverseSourceItem(path)
      .map(path.toRelative(_).toNIO)
      .map { _.iterator().asScala.map(p => wrap(p.toString())).toVector }
      .getOrElse(Vector.empty)

  private def calcPkgEdit(
      oldPackages: List[PkgWithName],
      newPkgs: List[String],
      oldPath: AbsolutePath,
  ): Option[WorkspaceEdit] = {
    val edits = findPkgEdits(newPkgs, oldPackages)
    if (edits.isEmpty) None
    else
      for {
        source <- buffers.get(oldPath).orElse(oldPath.readTextOpt)
      } yield pkgEditsToWorkspaceEdit(edits, source.toCharArray(), oldPath)
  }

  /**
   * Splits package declaration into statements
   * Desired property - not to break old visibility regions
   */
  private def findPkgEdits(
      newPkgs: List[String],
      oldPkg: List[PkgWithName],
  ): List[PkgEdit] =
    oldPkg match {
      case Nil => Nil
      case PkgWithName(oldPkg, _) :: Nil => List(PkgEdit(oldPkg, newPkgs))
      case PkgWithName(oldPkg, oldPkgName) :: nextOld =>
        newPkgs.zipWithIndex
          .filter { case (s, _) =>
            oldPkgName.lastOption.contains(s)
          }
          .minByOption { case (_, i) => (i - oldPkgName.length).abs } match {
          case None =>
            PkgEdit(oldPkg, List()) :: findPkgEdits(
              newPkgs,
              nextOld,
            )
          case Some((_, i)) =>
            val (currParts, nextNew) = newPkgs.splitAt(i + 1)
            PkgEdit(oldPkg, currParts) ::
              findPkgEdits(
                nextNew,
                nextOld,
              )
        }
    }

  private def pkgEditsToWorkspaceEdit(
      edits: List[PkgEdit],
      source: Array[Char],
      oldPath: AbsolutePath,
  ): WorkspaceEdit = {
    val extend: (Int, Int) => (Int, Int) =
      extendRangeToIncludeWhiteCharsAndTheFollowingNewLine(source, List(':'))
    edits.flatMap {
      // delete package declaration
      case PkgEdit(pkg, Nil) =>
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
            oldPath,
            "",
            new m.Position.Range(pkg.pos.input, startOffset, endOffset).toLsp,
          )
        }
      // rename package
      case PkgEdit(pkg, newParts) =>
        val edit =
          workspaceEdit(oldPath, newParts.mkString("."), pkg.ref.pos.toLsp)
        List(edit)
    }.mergeChanges
  }

  private def mkImportees(names: List[String]): String =
    names match {
      case List(single) => single
      case list => list.mkString("{", ", ", "}")
    }

  private def posNextToEndOf(pos: m.Position): Range = {
    val start = pos.end + 1
    m.Position.Range(pos.input, start, start).toLsp
  }

}

object PackageProvider {
  final case class DirChange(oldPkg: Vector[String], newPkg: Vector[String])

  @tailrec
  private def extractNames(
      t: Term,
      acc: Vector[String] = Vector.empty,
  ): Vector[String] = {
    t match {
      case n: Term.Name => n.value +: acc
      case s: Term.Select => extractNames(s.qual, s.name.value +: acc)
    }
  }

  case class PkgWithName(pkg: Pkg, name: Vector[String])

  case class PkgsStructure(
      pkgs: List[PkgWithName],
      pkgObject: Option[Pkg.Object] = None,
  ) {
    def withObject(pkgObject: Pkg.Object): PkgsStructure =
      PkgsStructure(pkgs, Some(pkgObject))
    def addPkg(pkg: Pkg): PkgsStructure =
      PkgsStructure(PkgWithName(pkg, extractNames(pkg.ref)) :: pkgs, pkgObject)
    def reverse(): PkgsStructure = PkgsStructure(pkgs.reverse, pkgObject)
    def allParts(): List[String] =
      pkgs.flatMap(_.name) ++ pkgObject.map(_.name.value).toList
  }

  object PkgsStructure {
    def empty: PkgsStructure = PkgsStructure(List(), None)
  }

  case class PkgEdit(
      pkg: Pkg,
      renameToParts: List[String], // empty list means delete
  )
}
