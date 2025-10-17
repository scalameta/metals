package scala.meta.internal.metals

import java.util.ArrayList

import scala.meta.pc.Node
import scala.meta.pc.VirtualFileParams

import org.eclipse.{lsp4j => l}

class SemanticTokensProviderV2(
    params: VirtualFileParams,
    nodes: collection.Seq[Node]
) {
  // Five integers per node
  private val tokens = new ArrayList[Integer](nodes.size * 5)
  def provide(): l.SemanticTokens = {
    var prev: l.Position = new l.Position(0, 0)
    nodes.foreach { node =>
      prev = handleNode(prev, node)
    }
    new l.SemanticTokens(tokens)
  }

  private def handleNode(prev: l.Position, node: Node): l.Position = {
    val startLine = params.offsetToLine(node.start())
    val startLineOffset = params.lineToOffset(startLine)
    val endLine = params.offsetToLine(node.end())
    if (startLine != endLine) {
      // Ignore multi-line tokens
      throw new IllegalArgumentException(
        s"Multi-line token: ${node.start()} -> ${node.end()}"
      )
      // return prev
    }

    val length = node.end() - node.start()
    val startCharacter = node.start() - startLineOffset

    // at index 5*i - deltaLine: token line number, relative to the start of the
    // previous token
    val deltaLine = startLine - prev.getLine()
    tokens.add(deltaLine)
    // at index 5*i+1 - deltaStart: token start character, relative to the start
    // of the previous token (relative to 0 or the previous token’s start if
    // they are on the same line)
    val prevCharacter =
      if (startLine == prev.getLine()) prev.getCharacter() else 0
    val deltaStart = startCharacter - prevCharacter
    tokens.add(deltaStart)
    // at index 5*i+2 - length: the length of the token.
    tokens.add(length)
    // at index 5*i+3 - tokenType: will be looked up in
    // SemanticTokensLegend.tokenTypes. We currently ask that tokenType < 65536.
    tokens.add(node.tokenType())
    // at index 5*i+4 - tokenModifiers: each set bit will be looked up in
    // SemanticTokensLegend.tokenModifiers
    tokens.add(node.tokenModifier())

    // Important: use the *start* character of this token. Per the spec:
    // > at index 5*i+1 - deltaStart: token start character, relative to the
    // **start** of the previous token (relative to 0 or the previous token’s
    // start if they are on the same line)
    new l.Position(startLine, startCharacter)
  }

}
