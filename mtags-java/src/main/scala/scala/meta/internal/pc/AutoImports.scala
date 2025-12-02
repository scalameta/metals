package scala.meta.internal.pc

import com.sun.source.tree.CompilationUnitTree
import com.sun.source.util.JavacTask
import com.sun.source.util.Trees
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit

import scala.jdk.CollectionConverters._

object AutoImports {

  case class AutoImportEdits(
      identifierEdit: Option[TextEdit],
      importTextEdits: List[TextEdit]
  )

  def autoImportPosition(
      compiler: JavaMetalsGlobal,
      task: JavacTask,
      root: CompilationUnitTree,
      newImportName: String
  ): Position = {

    val sourcePositions = Trees.instance(task).getSourcePositions()
    val text = root.getSourceFile.getCharContent(true)
    val imports = root.getImports.asScala.toList

    if (imports.nonEmpty) {
      val (importsBefore, importsAfter) = imports.span { imp =>
        imp.getQualifiedIdentifier.toString < newImportName
      }

      val (endPos, lineOffset) = importsAfter.headOption match {
        case Some(_) if importsBefore.nonEmpty =>
          (sourcePositions.getEndPosition(root, importsBefore.last).toInt, 1)

        case Some(_) =>
          (sourcePositions.getEndPosition(root, importsAfter.head).toInt, 0)

        case None =>
          (sourcePositions.getEndPosition(root, imports.last).toInt, 1)
      }

      val pos = compiler.offsetToPosition(endPos, text.toString)
      new Position(pos.getLine + lineOffset, 0)
    } else {
      val packageName = root.getPackageName
      if (packageName != null) {
        val endPos = compiler.offsetToPosition(
          sourcePositions.getEndPosition(root, packageName).toInt,
          text.toString
        )
        new Position(endPos.getLine + 2, 0)
      } else {
        new Position(0, 0)
      }
    }
  }

  def computeAutoImportEdits(
      compiler: JavaMetalsGlobal,
      task: JavacTask,
      root: CompilationUnitTree,
      className: String,
      identifierRange: Range
  ): AutoImportEdits = {
    val simpleName = className.split('.').lastOption.getOrElse(className)
    val isImported = isSimpleNameAlreadyImported(root, simpleName, className)

    if (isImported) {
      AutoImportEdits(Some(new TextEdit(identifierRange, className)), Nil)
    } else {
      val pos = autoImportPosition(compiler, task, root, className)
      val importText = s"import $className;\n"
      val edit = new TextEdit(new Range(pos, pos), importText)
      AutoImportEdits(None, List(edit))
    }
  }

  def isSimpleNameAlreadyImported(
      root: CompilationUnitTree,
      simpleName: String,
      className: String
  ): Boolean = {
    root.getImports.asScala
      .map(_.getQualifiedIdentifier.toString)
      .filterNot(_.endsWith(".*"))
      .filter { qualified =>
        val lastDot = qualified.lastIndexOf('.')
        val simple =
          if (lastDot >= 0) qualified.substring(lastDot + 1) else qualified
        simple == simpleName && className != qualified
      }
      .nonEmpty
  }
}
