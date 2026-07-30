package scala.meta.internal.jpc

import com.sun.tools.javac.parser.Tokens.Comment.CommentStyle

// A comment's start and (exclusive) end offset in the source.
private[internal] final case class JavaComment(
    start: Int,
    end: Int,
    style: CommentStyle
) {
  // NOTE: only plain `//` comments count as line comments. On JDK 23+ a `///`
  // markdown doc comment (JEP 467) is reported as its own `JAVADOC_LINE` style,
  // so it is not treated as a line comment here and does not fold (it has no
  // `*/`, so the block-comment path skips it too). On JDK < 23 `///` is a plain
  // `LINE` comment and does fold.
  def isLineComment: Boolean = style == CommentStyle.LINE
}
