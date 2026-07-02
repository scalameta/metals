package scala.meta.internal.jpc

import scala.jdk.CollectionConverters._

import com.sun.source.tree.CompilationUnitTree
import com.sun.source.tree.ImportTree
import com.sun.source.tree.LineMap
import com.sun.source.util.TreePath
import com.sun.source.util.Trees
import org.eclipse.{lsp4j => l}

/**
 * Shared class to manage the insertion of Java auto-imports.
 */
class JavaAutoImportEditor(path: TreePath, trees: Trees, fqn: String) {

  def textEdit(): l.TextEdit = {
    closestImport()
      .orElse(packageLine())
      .getOrElse(firstLineImport())
  }

  // There's an existing package line but no imports so we place the import
  // after the package line with a blank line between.
  private def packageLine(): Option[l.TextEdit] = {
    val compUnit = path.getCompilationUnit
    val packageTree = compUnit.getPackage
    val lineMap = compUnit.getLineMap
    if (packageTree != null && lineMap != null) {
      val positions = trees.getSourcePositions
      val packageName = packageTree.getPackageName
      var line = 0
      var length = 0
      if (packageName.toString.equals("<error>")) {
        val startPosition = positions.getStartPosition(compUnit, packageTree)
        line = lineMap.getLineNumber(startPosition).toInt
      } else {
        val endPosition = positions.getEndPosition(compUnit, packageName).toInt
        line = lineMap.getLineNumber(endPosition).toInt
        val text = compUnit.getSourceFile.getCharContent(true).toString
        val remaining = text.substring(endPosition)
        if (remaining.trim.startsWith(";")) {
          val index = remaining.indexOf(";")
          length = endPosition + index + 1
        } else {
          length = endPosition
        }
      }
      if (line > 0) {
        line -= 1;
      };
      val edit = new l.TextEdit(
        new l.Range(
          new l.Position(line, length),
          new l.Position(line, length)
        ),
        s"\n\nimport $fqn;\n"
      )
      Some(edit)
    } else {
      None
    }
  }

  // There's no existing package or import line so we place the import at the
  // top of the file followed by a blank line.
  private def firstLineImport(): l.TextEdit = new l.TextEdit(
    new l.Range(
      new l.Position(0, 0),
      new l.Position(0, 0)
    ),
    s"import $fqn;\n\n"
  )

  // The file has imports, so we place the auto-import below/above the closest
  // match (the import with the longest shared prefix with the fqn).
  private def closestImport(): Option[l.TextEdit] = {
    val compUnit: CompilationUnitTree = path.getCompilationUnit
    val imports: java.util.List[_ <: ImportTree] = compUnit.getImports
    val result: Option[ImportTree] = findCandidate(imports, fqn)
    result match {
      case Some(candidate) =>
        val lineMap: LineMap = compUnit.getLineMap
        val importFqn = candidate.getQualifiedIdentifier.toString
        val positions = trees.getSourcePositions
        val start = positions.getStartPosition(compUnit, candidate)
        val end = positions.getEndPosition(compUnit, candidate)
        val length = end - start
        var line = lineMap.getLineNumber(start).toInt
        if (line > 0) {
          line -= 1
        }
        val (character, insertText) =
          if (isGreater(importFqn, fqn)) {
            (0, s"import $fqn;\n")
          } else {
            (length.toInt, s"\nimport $fqn;")
          }
        val edit = new l.TextEdit(
          new l.Range(
            new l.Position(line, character),
            new l.Position(line, character)
          ),
          insertText
        )
        Some(edit)
      case None =>
        None
    }

  }

  private def isGreater(importFqn: String, fqn: String): Boolean = {
    s"$fqn".compareTo(importFqn) < 0
  }

  private def importPrefixMatchLength(importFqn: String, fqn: String): Int = {
    importFqn.view.zip(fqn).takeWhile { case (c1, c2) => c1 == c2 }.size
  }

  private def findCandidate(
      allImports: java.util.List[_ <: ImportTree],
      fqn: String
  ): Option[ImportTree] = {
    val imports: Seq[ImportTree] = allImports.asScala.toSeq
    if (imports.isEmpty) {
      None
    } else {
      val result: Seq[(ImportTree, Int)] = imports.map { imp =>
        var importFqn = imp.getQualifiedIdentifier.toString
        if (importFqn.endsWith(".*")) {
          importFqn = importFqn.stripSuffix(".*")
        }
        val length = importPrefixMatchLength(importFqn, fqn)
        (imp, length)
      }
      val (candidate, maxLength) = result.maxBy(_._2)
      if (maxLength > 0) Some(candidate) else None
    }
  }

}

object JavaAutoImportEditor {

  /**
   * A single edit that inserts the given imports as a block, placed after the
   * last existing import, or after the package declaration, or at the top of
   * the file. Returns `None` when there are no imports to add.
   */
  def imports(text: String, fqns: List[String]): Option[l.TextEdit] = {
    if (fqns.isEmpty) None
    else {
      val block = fqns.map(fqn => s"import $fqn;").mkString("\n")
      ImportLine.fromText(text).lastOption match {
        case Some(lastImport) =>
          Some(
            insertAt(
              lastImport.lineNumber,
              lastImport.line.length(),
              s"\n$block"
            )
          )
        case None =>
          packageLine(text) match {
            case Some((lineNumber, length)) =>
              Some(insertAt(lineNumber, length, s"\n\n$block"))
            case None =>
              Some(insertAt(0, 0, s"$block\n\n"))
          }
      }
    }
  }

  private val PackageDeclaration = """^\s*package\s+.*""".r

  private def packageLine(text: String): Option[(Int, Int)] =
    text.linesIterator.zipWithIndex.collectFirst {
      case (line, lineNumber)
          if PackageDeclaration.pattern.matcher(line).matches() =>
        (lineNumber, line.length())
    }

  private def insertAt(
      line: Int,
      character: Int,
      newText: String
  ): l.TextEdit = {
    val position = new l.Position(line, character)
    new l.TextEdit(new l.Range(position, position), newText)
  }
}
