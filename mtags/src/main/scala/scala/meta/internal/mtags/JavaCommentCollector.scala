package scala.meta.internal.mtags

import java.io.PrintWriter
import java.io.StringWriter

import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer
import scala.util.Try

import com.sun.tools.javac.parser.JavaTokenizer
import com.sun.tools.javac.parser.ScannerFactory
import com.sun.tools.javac.parser.Tokens.Comment
import com.sun.tools.javac.parser.Tokens.Comment.CommentStyle
import com.sun.tools.javac.parser.Tokens.TokenKind
import com.sun.tools.javac.util.Context
import com.sun.tools.javac.util.Log
import com.sun.tools.javac.util.Options

object JavaCommentCollector {

  // A comment's start and (exclusive) end offset in the source.
  final case class JavaComment(start: Int, end: Int, isLineComment: Boolean)

  // All comments (block, Javadoc and line) in source order. Returns Nil if the
  // source cannot be tokenized.
  def comments(text: String): List[JavaComment] =
    Try(tokenize(text)).getOrElse(Nil)

  private def tokenize(text: String): List[JavaComment] = {
    val context = new Context()

    // Register a Log that discards output before anything calls
    // `Log.instance` (which `ScannerFactory.instance` does). The lexer reports
    // errors - unclosed comments, illegal characters, bad escapes - through it,
    // and folding runs constantly on in-progress, often-malformed source, so
    // without this those errors would be printed to System.err.
    // `preRegister` must run before `Log.instance`, otherwise it throws.
    val silent = new PrintWriter(new StringWriter())
    Log.preRegister(context, silent)
    val options = Options.instance(context)

    // Enable preview features so that source using them still tokenizes instead
    // of erroring out (we only care about comments, not language-level checks).
    options.put("--enable-preview", "true")

    // Don't fold string literals: it keeps the lexer's work minimal and avoids
    // touching literal contents, which are irrelevant to comment positions.
    options.put("allowStringFolding", "false")

    // Reads Log/Options from the context, so it must come after they are set.
    val factory = ScannerFactory.instance(context)

    val tokenizer =
      new CommentCollectingTokenizer(factory, text)

    // Drive the lexer to EOF; comments are gathered via `processComment`.
    @tailrec def drain(): Unit = {
      if (tokenizer.readToken().kind != TokenKind.EOF) {
        drain()
      }
    }
    drain()
    tokenizer.comments.toList
  }

  // javac reports comments through the `processComment` callback, so the only
  // mutable state is this tokenizer's buffer; the rest of the collector is
  // pure. Named (not anonymous) so `comments` is a plain field rather than a
  // structural/reflective member access.
  private final class CommentCollectingTokenizer(
      factory: ScannerFactory,
      input: String
  ) extends JavaTokenizer(factory, input.toCharArray(), input.length()) {
    val comments: ListBuffer[JavaComment] = ListBuffer.empty
    override protected def processComment(
        pos: Int,
        endPos: Int,
        style: CommentStyle
    ): Comment = {
      // Match on the enum name so this keeps working across JDKs that split
      // JAVADOC into JAVADOC_LINE / JAVADOC_BLOCK.
      val isLineComment = style.name().contains("LINE")
      comments += JavaComment(pos, endPos, isLineComment)
      super.processComment(pos, endPos, style)
    }
  }
}
