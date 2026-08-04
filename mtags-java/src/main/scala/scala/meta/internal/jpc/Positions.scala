package scala.meta.internal.jpc

import com.sun.source.tree.CompilationUnitTree
import com.sun.source.tree.LineMap
import com.sun.source.tree.Tree
import com.sun.source.util.Trees
import org.eclipse.{lsp4j => l}
object Positions {

  private val LegacyArrayDimensions = """\s*(?:\[\s*\]\s*)+""".r

  def findNameOffset(
      text: String,
      start: Int,
      end: Int,
      name: String
  ): Option[Int] = {
    val searchEnd = Math.min(end, text.length())
    if (start < 0 || end < 0 || name.isEmpty) None
    else
      (start until searchEnd).find { offset =>
        val nameEnd = offset + name.length()
        Character.isJavaIdentifierStart(text.charAt(offset)) &&
        text.startsWith(name, offset) &&
        (offset == 0 ||
          !Character.isJavaIdentifierPart(text.charAt(offset - 1))) &&
        (nameEnd >= text.length() ||
          !Character.isJavaIdentifierPart(text.charAt(nameEnd)))
      }
  }

  def trimLegacyArraySuffix(
      typeStart: Int,
      typeEnd: Int,
      nameStart: Int,
      nameEnd: Int,
      lineMap: LineMap,
      text: String
  ): Option[(l.Range, Int)] = {
    val hasLegacyArraySuffix =
      typeEnd > nameEnd &&
        LegacyArrayDimensions.pattern
          .matcher(text.substring(nameEnd, typeEnd))
          .matches()
    if (hasLegacyArraySuffix) {
      val end = lastNonWhitespaceBefore(text, nameStart) + 1
      Option.when(end > typeStart)(
        (toLspRange(lineMap, typeStart, end, text), end)
      )
    } else
      Some((toLspRange(lineMap, typeStart, typeEnd, text), typeEnd))
  }

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

  def toLspRange(
      lineMap: LineMap,
      start: Long,
      end: Long,
      text: String
  ): l.Range = {
    new l.Range(
      toLspPosition(lineMap, start, text),
      // end=-1 when it's an offset range
      toLspPosition(lineMap, math.max(start, end), text)
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
    val startPos = lineMap.getPosition(line, 0).intValue().max(0)
    var offset = startPos
    while (offset < text.length() && text.charAt(offset) == '\t') {
      offset += 1
    }
    val tabCount = offset - startPos
    tabCount * 7
  }

  private def lastNonWhitespaceBefore(text: String, offset: Int): Int = {
    var index = offset - 1
    while (index >= 0 && text.charAt(index).isWhitespace) index -= 1
    index
  }
}
