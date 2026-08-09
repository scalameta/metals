package scala.meta.internal.jpc

import com.sun.tools.javac.parser.Scanner
import com.sun.tools.javac.parser.ScannerFactory
import com.sun.tools.javac.util.Context

// Produces scanners whose tokenizer reports every comment to `onComment`.
// Registering an instance in a javac `Context` (its constructor puts itself
// there) makes the parser collect comments during a normal parse.
private[jpc] final class CommentCollectingScannerFactory(
    context: Context,
    onComment: JavaComment => Unit
) extends ScannerFactory(context) {
  override def newScanner(
      input: CharSequence,
      keepDocComments: Boolean
  ): Scanner = {
    val array = input.toString.toCharArray
    newScanner(array, array.length, keepDocComments)
  }
  override def newScanner(
      input: Array[Char],
      inputLength: Int,
      keepDocComments: Boolean
  ): Scanner =
    new CommentCollectingScanner(
      this,
      new CommentCollectingTokenizer(this, input, inputLength, onComment)
    )
}
