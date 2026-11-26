package scala.meta.internal.mtags

/**
 * Splits Kotlin source text into identifiers, keywords and symbols. String
 * and character literals and comments are skipped, but the code inside a
 * string template (`$name`, `${expr}`) is tokenized.
 */
final class KotlinLexer(text: String, startOffset: Int = 0) {
  import KotlinLexer._

  private var offset = startOffset
  private var newline = true
  // One entry per open string; a code entry counts braces inside `${ }`.
  private val modes = new java.util.ArrayDeque[Mode]()

  private sealed abstract class Mode
  private final class InString(val raw: Boolean) extends Mode
  private final class InTemplate(var depth: Int) extends Mode

  // True when the last token came from a string template.
  private var fromTemplate = false
  def lastFromTemplate: Boolean = fromTemplate

  /**
   * Returns the next token. Inside `${ }` only identifiers are returned, so
   * that template code cannot change the parser state around the string.
   */
  def next(): Token = {
    var t = scanOne()
    while (
      t == null ||
      (fromTemplate && t != Token.EOF && !t.isInstanceOf[Token.Ident])
    ) t = scanOne()
    t
  }

  /**
   * The next token, or null when the scanner only enters or leaves a string
   * or a template. The loop in `next` reads a long string with many
   * templates in constant stack space.
   */
  private def scanOne(): Token =
    if (!modes.isEmpty && modes.peek().isInstanceOf[InString])
      scanString(modes.peek().asInstanceOf[InString])
    else scanCode()

  /**
   * True when `<` at `start` opens type arguments such as
   * `emptyMap<String, Int>()`: the text up to the matching `>` has only
   * names and type punctuation.
   */
  def looksLikeTypeArguments(start: Int): Boolean = {
    var i = start
    var depth = 0
    var result: Option[Boolean] = None
    while (result.isEmpty && i < text.length) {
      val c = text.charAt(i)
      if (c == '<') depth += 1
      else if (c == '>' && text.charAt(i - 1) != '-') {
        depth -= 1
        if (depth == 0) result = Some(true)
      } else if (!Character.isJavaIdentifierPart(c) && TypeChars.indexOf(c) < 0)
        result = Some(false)
      i += 1
    }
    result.getOrElse(false)
  }

  /** True when `.` at the current position is followed by an identifier. */
  def lookaheadIsIdentifierAfterDot(): Boolean =
    // `offset` is after the peeked `.`
    new KotlinLexer(text, offset).next().isInstanceOf[Token.Ident]

  private def ch(i: Int): Char =
    if (i < text.length) text.charAt(i) else EOFChar

  private def scanCode(): Token = {
    skipWhitespaceAndComments()
    fromTemplate = !modes.isEmpty
    if (offset >= text.length) return Token.EOF
    val nl = newline
    newline = false
    val c = text.charAt(offset)
    if (c == '"') {
      if (ch(offset + 1) == '"' && ch(offset + 2) == '"') {
        offset += 3
        modes.push(new InString(raw = true))
      } else {
        offset += 1
        modes.push(new InString(raw = false))
      }
      null
    } else if (c == '\'') {
      val start = offset
      skipCharLiteral()
      Token.Sym('\'', nl)(start)
    } else if (c == '`') {
      val start = offset + 1
      var i = start
      while (i < text.length && text.charAt(i) != '`' && text.charAt(i) != '\n')
        i += 1
      offset = math.min(i + 1, text.length)
      Token.Ident(text.substring(start, i), start, i)(nl)
    } else if (Character.isJavaIdentifierStart(c)) {
      val start = offset
      var i = offset + 1
      while (i < text.length && Character.isJavaIdentifierPart(text.charAt(i)))
        i += 1
      offset = i
      val name = text.substring(start, i)
      if (HardKeywords.contains(name)) Token.Keyword(name, nl)(start, i)
      else Token.Ident(name, start, i)(nl)
    } else if (Character.isDigit(c)) {
      val start = offset
      skipNumber()
      Token.Sym('0', nl)(start)
    } else {
      offset += 1
      if (!modes.isEmpty) {
        val template = modes.peek().asInstanceOf[InTemplate]
        if (c == '{') template.depth += 1
        else if (c == '}') {
          if (template.depth == 0) {
            modes.pop() // back inside the string
            return null
          }
          template.depth -= 1
        }
      }
      Token.Sym(c, nl)(offset - 1)
    }
  }

  private def scanString(mode: InString): Token = {
    var result: Token = null
    var done = false
    while (!done) {
      done = true
      if (offset >= text.length) {
        modes.clear()
        result = Token.EOF
      } else {
        val c = text.charAt(offset)
        if (!mode.raw && c == '\\') { offset += 2; done = false }
        else if (!mode.raw && (c == '"' || c == '\n')) {
          offset += 1
          modes.pop()
          fromTemplate = !modes.isEmpty
          result = Token.Sym('"', false)(offset - 1)
        } else if (
          mode.raw && c == '"' && ch(offset + 1) == '"' && ch(
            offset + 2
          ) == '"'
        ) {
          offset += 3
          while (ch(offset) == '"') offset += 1 // `""""` ends with a quote
          modes.pop()
          fromTemplate = !modes.isEmpty
          result = Token.Sym('"', false)(offset - 1)
        } else if (c == '$' && ch(offset + 1) == '{') {
          offset += 2
          modes.push(new InTemplate(0))
        } else if (
          c == '$' && ch(offset + 1) != '$' &&
          Character.isJavaIdentifierStart(ch(offset + 1))
        ) {
          val start = offset + 1
          var i = start + 1
          while (i < text.length && isTemplateNamePart(text.charAt(i)))
            i += 1
          offset = i
          val name = text.substring(start, i)
          fromTemplate = true
          result =
            if (HardKeywords.contains(name))
              Token.Keyword(name, false)(start, i)
            else Token.Ident(name, start, i)(false)
        } else { offset += 1; done = false }
      }
    }
    result
  }

  // A `$name` template ends at the next `$`: `"$first$last"` has two names.
  private def isTemplateNamePart(c: Char): Boolean =
    c != '$' && Character.isJavaIdentifierPart(c)

  private def skipCharLiteral(): Unit = {
    offset += 1
    if (ch(offset) == '\\') offset += 2 else offset += 1
    while (
      offset < text.length && text.charAt(offset) != '\'' &&
      text.charAt(offset) != '\n'
    ) offset += 1
    if (ch(offset) == '\'') offset += 1
  }

  private def skipNumber(): Unit = {
    while (
      offset < text.length && {
        val c = text.charAt(offset)
        Character.isLetterOrDigit(c) || c == '_' ||
        (c == '.' && Character.isDigit(ch(offset + 1)))
      }
    ) offset += 1
  }

  private def skipWhitespaceAndComments(): Unit = {
    var continue = true
    while (continue && offset < text.length) {
      val c = text.charAt(offset)
      if (c == '\n') { newline = true; offset += 1 }
      else if (Character.isWhitespace(c)) offset += 1
      else if (c == '/' && ch(offset + 1) == '/') {
        while (offset < text.length && text.charAt(offset) != '\n')
          offset += 1
      } else if (c == '/' && ch(offset + 1) == '*') {
        // Kotlin block comments nest.
        var depth = 1
        offset += 2
        while (depth > 0 && offset < text.length) {
          if (text.charAt(offset) == '/' && ch(offset + 1) == '*') {
            depth += 1; offset += 2
          } else if (text.charAt(offset) == '*' && ch(offset + 1) == '/') {
            depth -= 1; offset += 2
          } else {
            if (text.charAt(offset) == '\n') newline = true
            offset += 1
          }
        }
      } else if (c == '#' && offset == 0 && ch(1) == '!') {
        while (offset < text.length && text.charAt(offset) != '\n')
          offset += 1
      } else continue = false
    }
  }
}

object KotlinLexer {

  /** Hard keywords: never identifiers, so never references. */
  val HardKeywords: Set[String] = Set(
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun",
    "if", "in", "interface", "is", "null", "object", "package", "return",
    "super", "this", "throw", "true", "try", "typealias", "typeof", "val",
    "var", "when", "while"
  )

  sealed abstract class Token {
    def newlineBefore: Boolean
  }
  object Token {
    case object BOF extends Token { def newlineBefore = true }
    case object EOF extends Token { def newlineBefore = true }
    final case class Ident(name: String, start: Int, end: Int)(
        val newlineBefore: Boolean
    ) extends Token
    final case class Keyword(name: String, newlineBefore: Boolean)(
        val start: Int,
        val end: Int
    ) extends Token
    final case class Sym(char: Char, newlineBefore: Boolean)(val start: Int)
        extends Token
  }

  private final val EOFChar = '\u001a'
  // Characters that can appear in type arguments besides names.
  private val TypeChars = " \t\r\n,.?*()-@`<>"
}
