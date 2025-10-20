package scala.meta.internal.jpc

import com.sun.source.tree.CompilationUnitTree
import com.sun.source.tree.LineMap
import com.sun.source.tree.Tree
import com.sun.source.util.Trees
import org.eclipse.{lsp4j => l}
object Positions {

  def toLspRange(
      trees: Trees,
      cu: CompilationUnitTree,
      tree: Tree
  ): l.Range = {
    val text = cu.getSourceFile().getCharContent(true).toString()
    val start = trees.getSourcePositions().getStartPosition(cu, tree)
    val end = trees.getSourcePositions().getEndPosition(cu, tree)
    new l.Range(
      toLspPosition(cu.getLineMap(), start, text),
      toLspPosition(cu.getLineMap(), end, text)
    )
  }
  def toLspPosition(
      lineMap: LineMap,
      offset: Long,
      text: String
  ): l.Position = {
    val line = lineMap.getLineNumber(offset).intValue()
    val character =
      lineMap.getColumnNumber(offset).intValue()
    val tabsOffset = countTabsOffset(lineMap, line, text)
    // LSP positions are 0-indexed
    new l.Position(line - 1, character - tabsOffset - 1)
  }

  // javac treats tabs as 8 characters, this function returns what we need to
  // subtract for this line.
  private def countTabsOffset(
      lineMap: LineMap,
      line: Int,
      text: String
  ): Int = {
    val startPos = lineMap.getPosition(line, 0).intValue()
    var offset = startPos
    while (offset < text.length() && text.charAt(offset) == '\t') {
      offset += 1
    }
    val tabCount = offset - startPos
    tabCount * 7
  }
}
