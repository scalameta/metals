package scala.meta.internal.jpc

import com.sun.tools.javac.parser.JavaTokenizer
import com.sun.tools.javac.parser.ScannerFactory
import com.sun.tools.javac.parser.Tokens.Comment
import com.sun.tools.javac.parser.Tokens.Comment.CommentStyle

// javac reports comments through the `processComment` callback
private[jpc] final class CommentCollectingTokenizer(
    factory: ScannerFactory,
    input: Array[Char],
    inputLength: Int,
    onComment: JavaComment => Unit
) extends JavaTokenizer(factory, input, inputLength) {
  override protected def processComment(
      pos: Int,
      endPos: Int,
      style: CommentStyle
  ): Comment = {
    onComment(JavaComment(pos, endPos, style))
    super.processComment(pos, endPos, style)
  }
}
