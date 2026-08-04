package scala.meta.internal.jpc

import com.sun.source.tree.CompilationUnitTree
import com.sun.source.tree.LineMap
import com.sun.source.tree.Tree
import com.sun.source.util.Trees
import com.sun.tools.javac.parser.ScannerFactory
import com.sun.tools.javac.parser.Tokens.TokenKind
import com.sun.tools.javac.util.Context
import org.eclipse.{lsp4j => l}
object Positions {

  private val ScannerFactoryThreadLocal = new ThreadLocal[ScannerFactory] {
    override def initialValue(): ScannerFactory =
      ScannerFactory.instance(new Context())
  }

  def findNameOffset(
      text: String,
      start: Int,
      end: Int,
      name: String
  ): Option[Int] = {
    if (start < 0 || end < 0 || name.isEmpty) None
    else
      foldTokens(text, start, end, Option.empty[Int]) {
        case (result @ Some(_), _, _) => result
        case (None, tokenStart, tokenEnd) =>
          Option.when(
            tokenEnd - tokenStart == name.length() &&
              text.startsWith(name, tokenStart)
          )(tokenStart)
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
    val hasLegacyArraySuffix = typeEnd > nameEnd
    if (hasLegacyArraySuffix) {
      val end = lastCodeTokenEnd(text, typeStart, nameStart)
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

  private def lastCodeTokenEnd(
      text: String,
      start: Int,
      end: Int
  ): Int =
    foldTokens(text, start, end, start) { (_, _, tokenEnd) => tokenEnd }

  private def foldTokens[A](
      text: String,
      start: Int,
      end: Int,
      initial: A
  )(f: (A, Int, Int) => A): A = {
    val sourceStart = start.max(0)
    val sourceEnd = end.min(text.length())
    if (sourceStart >= sourceEnd) initial
    else {
      val scanner = ScannerFactoryThreadLocal
        .get()
        .newScanner(text.substring(sourceStart, sourceEnd), false)
      var result = initial
      scanner.nextToken()
      while (scanner.token().kind != TokenKind.EOF) {
        val token = scanner.token()
        result = f(
          result,
          sourceStart + token.pos,
          sourceStart + token.endPos
        )
        scanner.nextToken()
      }
      result
    }
  }
}
