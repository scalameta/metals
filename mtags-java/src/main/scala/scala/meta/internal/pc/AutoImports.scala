package scala.meta.internal.pc

import scala.jdk.CollectionConverters._

import com.sun.source.tree.CompilationUnitTree
import com.sun.source.util.JavacTask
import com.sun.source.util.Trees
import org.eclipse.lsp4j.Position

object AutoImports {

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

      val endPos = importsAfter.headOption match {
        case Some(_) if importsBefore.nonEmpty =>
          sourcePositions.getEndPosition(root, importsBefore.last).toInt

        case Some(_) =>
          sourcePositions.getEndPosition(root, importsAfter.head).toInt

        case None =>
          sourcePositions.getEndPosition(root, imports.last).toInt
      }

      val pos = compiler.offsetToPosition(endPos, text.toString)
      new Position(pos.getLine + 1, 0)
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
}
