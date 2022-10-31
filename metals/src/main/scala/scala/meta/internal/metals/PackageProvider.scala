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
    val edit = trees
      .get(oldPath)
      .map(tree => {
        val oldPkgParts = deducePkgParts(oldPath)
        val newPkgParts = deducePkgParts(newPath)
        val fileEdits = calcMovedFileEdits(tree, oldPath, newPkgParts)
        val refsEdits = calcRefsEdits(
          tree,
          oldPath,
          oldPkgParts,
          newPkgParts,
          optDirChange,
        )
        List(fileEdits, refsEdits).mergeChanges
      })

    edit.getOrElse(new WorkspaceEdit())
  }

  private def calcMovedFileEdits(
      tree: Tree,
      oldPath: AbsolutePath,
      newPkgParts: Vector[String],
  ): WorkspaceEdit = {
    val pkgs = findPkgs(tree)

    val oldPkgSplit: Vector[Vector[String]] =
      pkgs.map(p => extractNames(p.ref))

    val optPkgObj = tree.collect { case p: Pkg.Object => p }.headOption

    // edit of package stament
    val pkgStmtParts =
      if (optPkgObj.isDefined) newPkgParts.dropRight(1) else newPkgParts
    val pkgEdit = calcPkgEdit(oldPkgSplit, pkgStmtParts, oldPath, pkgs)

    // edit of package object name if it exists in the file
    val pkgObjEdit = for {
      pkgObjStmt <- optPkgObj
      lastPkg <- newPkgParts.lastOption
      edit = workspaceEdit(oldPath, lastPkg, pkgObjStmt.name.pos.toLsp)
      if pkgObjStmt.name.value != lastPkg
    } yield edit

    (List(pkgEdit) ++ pkgObjEdit).mergeChanges
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
    val importString = s"\nimport $newPkgName.$importees"

    val optLastPkg = for {
      tree <- optTree
      lastPkg <- findPkgs(tree).lastOption
    } yield lastPkg

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
            new TextEdit(newImportPos, s"import $newImportStr")
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

  private def calcPkgEdit(
      oldPackageSplit: Vector[Vector[String]],
      newPackageParts: Vector[String],
      oldPath: AbsolutePath,
      packages: Vector[Pkg],
  ): WorkspaceEdit =
    packages match {
      case firstPkg +: otherPkgs =>
        val lastPkg = otherPkgs.lastOption.getOrElse(firstPkg).ref.pos
        val range = firstPkg.ref.pos.toLsp.copy(
          startCharacter = 0,
          endLine = lastPkg.endLine,
          endCharacter = lastPkg.endColumn,
        )
        val newSplit = splitPackageStatements(oldPackageSplit, newPackageParts)
        val pkgStatement =
          newSplit.map(ps => s"package ${ps.mkString(".")}").mkString("\n")
        if (oldPackageSplit != newSplit)
          workspaceEdit(oldPath, pkgStatement, range)
        else new WorkspaceEdit()
      case _ => new WorkspaceEdit()
    }

  private def findPkgs(tree: Tree): Vector[Pkg] =
    tree.collect { case p: Pkg => p }.toVector

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

  /**
   * Splits package declaration into statements
   * Desired property - not to break old visibility regions
   * @param oldPackageSplit: content of package statements before refactoring
   * @param newPkgs: list of package parts that should be present after refactoring
   * @return split of `newPkgs` into package statements with respect to the `oldPackageSplit`
   */
  private def splitPackageStatements(
      oldPackageSplit: Vector[Vector[String]],
      newPkgs: Vector[String],
  ): Vector[Vector[String]] = {
    val oldPackageSplitSet = oldPackageSplit.toSet
    val oldEndPkgs =
      oldPackageSplit.flatMap(_.lastOption).reverse.zipWithIndex.toMap

    var consideredEndPkg = 0
    var pkgStmts: Vector[Vector[String]] = Vector.empty
    var pkgStmt: Vector[String] = Vector.empty

    newPkgs.reverse.foreach { newPkg =>
      val foundEndPkg =
        oldEndPkgs.get(newPkg).filter { _ >= consideredEndPkg }
      foundEndPkg match {
        case Some(endPkgInd) => {
          if (endPkgInd == 0) pkgStmt = newPkg +: pkgStmt
          else {
            if (pkgStmt.nonEmpty) pkgStmts = pkgStmt +: pkgStmts
            pkgStmt = Vector(newPkg)
          }
          consideredEndPkg = endPkgInd + 1
        }
        case None =>
          if (oldPackageSplitSet.contains(pkgStmt)) {
            pkgStmts = pkgStmt +: pkgStmts
            pkgStmt = Vector.empty
          }
          pkgStmt = newPkg +: pkgStmt
      }
    }
    if (pkgStmt.nonEmpty) pkgStmts = pkgStmt +: pkgStmts
    pkgStmts
  }

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
}
