package scala.meta.internal.jpc;
import java.{util => ju}

import scala.collection.mutable
import scala.jdk.CollectionConverters._

import scala.meta.internal.jpc.Positions
import scala.meta.pc.OffsetParams

import com.sun.source.tree.CompilationUnitTree
import com.sun.source.tree.LineMap
import com.sun.source.tree.Tree
import com.sun.source.util.TreePath
import com.sun.source.util.TreePathScanner
import com.sun.source.util.Trees
import org.eclipse.{lsp4j => l}

class JavaSelectionRangeProvider() {
  def provide(
      params: ju.List[OffsetParams]
  ): ju.List[l.SelectionRange] = {
    val cache = mutable.Map.empty[String, (Trees, CompilationUnitTree)]
    params.asScala.map { params =>
      val (trees, cu) = cache.getOrElseUpdate(
        params.uri().toString(),
        JavaMetalsCompiler.parse(params).get
      )
      val scanner = new SelectionRangePathScanner(trees, cu, params.offset())
      scanner.scan(cu, ())
      scanner.selectionRange
    }.asJava
  }

  private class SelectionRangePathScanner(
      trees: Trees,
      cu: CompilationUnitTree,
      offset: Int
  ) extends TreePathScanner[TreePath, Unit] {
    val text: String = cu.getSourceFile().getCharContent(true).toString()
    val lineMap: LineMap = cu.getLineMap()

    var selectionRange: l.SelectionRange = null
    override def scan(tree: Tree, p: Unit): TreePath = {
      val start = trees.getSourcePositions().getStartPosition(cu, tree)
      val end = trees.getSourcePositions().getEndPosition(cu, tree)
      if (offset < start || offset > end) {
        return null
      }
      selectionRange = new l.SelectionRange(
        new l.Range(
          Positions.toLspPosition(lineMap, start, text),
          Positions.toLspPosition(lineMap, end, text)
        ),
        selectionRange
      )
      super.scan(tree, p)
    }
  }
}
