package scala.meta.internal.mtags.proto

import scala.collection.mutable.ArrayBuffer

import scala.meta.internal.mtags.proto.ProtobufToken.TokenType

/**
 * A hand-written tokenizer for Protobuf v2 and v3.
 *
 * Warning: this file is >99% AI generated. The best way to work with this file
 * is to write test cases in ProtobufScannerSuite and let the AI fix the bugs.
 */
class ProtobufScanner(val text: String, val filepath: String) {
  private var pos: Int = 0
  private var start: Int = 0
  private var insideFieldOptions: Boolean = false

  def scanAll(): ArrayBuffer[ProtobufToken] = {
    val tokens = new ArrayBuffer[ProtobufToken]()
    var token = nextToken()
    while (token.tpe != ProtobufToken.EOF) {
      tokens += token
      token = nextToken()
    }
    tokens += token // Add the EOF token
    tokens
  }

  def nextToken(): ProtobufToken = {
    skipWhitespaceAndComments()
    start = pos
    if (isAtEnd) return makeToken(ProtobufToken.EOF)

    val char = advance()

    char match {
      case c if isIdentifierStart(c) => scanIdentifier()
      case c if c.isDigit => scanNumber()
      case '"' | '\'' => scanString(char)
      case '{' => makeToken(ProtobufToken.LEFT_BRACE)
      case '}' => makeToken(ProtobufToken.RIGHT_BRACE)
      case '(' => makeToken(ProtobufToken.LEFT_PAREN)
      case ')' => makeToken(ProtobufToken.RIGHT_PAREN)
      case '[' =>
        insideFieldOptions = true
        makeToken(ProtobufToken.LEFT_BRACKET)
      case ']' =>
        insideFieldOptions = false
        makeToken(ProtobufToken.RIGHT_BRACKET)
      case '<' => makeToken(ProtobufToken.LEFT_ANGLE)
      case '>' => makeToken(ProtobufToken.RIGHT_ANGLE)
      case ';' => makeToken(ProtobufToken.SEMICOLON)
      case ',' => makeToken(ProtobufToken.COMMA)
      case '=' => makeToken(ProtobufToken.EQUALS)
      case '.' => makeToken(ProtobufToken.DOT)
      case _ => makeToken(ProtobufToken.ILLEGAL)
    }
  }

  private def scanIdentifier(): ProtobufToken = {
    while (!isAtEnd && isIdentifierPart(peek())) {
      advance()
    }
    val lexeme = text.substring(start, pos)
    val tpe =
      if (
        lexeme == "default" && insideFieldOptions && isFollowedByEqualsAndSpecificToken(
          "UNIVERSAL"
        )
      ) {
        // Special case: [default = UNIVERSAL] is a custom field option, not proto2 default keyword
        ProtobufToken.IDENTIFIER
      } else {
        ProtobufToken.keywords.getOrElse(lexeme, ProtobufToken.IDENTIFIER)
      }
    makeToken(tpe)
  }

  private def scanNumber(): ProtobufToken = {
    while (!isAtEnd && peek().isDigit) {
      advance()
    }

    // Look for a fractional part
    if (!isAtEnd && peek() == '.' && peekNext().isDigit) {
      advance() // consume the '.'
      while (!isAtEnd && peek().isDigit) {
        advance()
      }
      return makeToken(ProtobufToken.FLOAT)
    }

    makeToken(ProtobufToken.INTEGER)
  }

  private def scanString(quoteType: Char): ProtobufToken = {
    while (!isAtEnd && peek() != quoteType) {
      advance()
    }

    if (isAtEnd) {
      // Unterminated string
      return makeToken(ProtobufToken.ILLEGAL)
    }

    advance() // Consume the closing quote
    makeToken(ProtobufToken.STRING)
  }

  private def skipWhitespaceAndComments(): Unit = {
    while (!isAtEnd) {
      peek() match {
        case ' ' | '\r' | '\t' | '\n' => advance()
        case '/' =>
          if (peekNext() == '/') {
            // Single-line comment
            while (!isAtEnd && peek() != '\n') advance()
          } else if (peekNext() == '*') {
            // Multi-line comment
            advance() // consume '/'
            advance() // consume '*'
            while (!isAtEnd && !(peek() == '*' && peekNext() == '/')) {
              advance()
            }
            if (!isAtEnd) {
              advance() // consume '*'
              advance() // consume '/'
            }
          } else {
            return // Not a comment, but some other '/'
          }
        case _ => return
      }
    }
  }

  // --- Helper Methods ---
  private def isAtEnd: Boolean = pos >= text.length
  private def advance(): Char = {
    pos += 1
    text.charAt(pos - 1)
  }
  private def peek(): Char = if (isAtEnd) '\u0000' else text.charAt(pos)
  private def peekNext(): Char =
    if (pos + 1 >= text.length) '\u0000' else text.charAt(pos + 1)
  private def isIdentifierStart(c: Char): Boolean = c.isLetter || c == '_'
  private def isIdentifierPart(c: Char): Boolean = c.isLetterOrDigit || c == '_'

  private def isFollowedByEqualsAndSpecificToken(
      expectedToken: String
  ): Boolean = {
    var i = pos
    // Skip whitespace
    while (i < text.length && " \t\n\r".contains(text.charAt(i))) {
      i += 1
    }
    // Check for '='
    if (i >= text.length || text.charAt(i) != '=') return false
    i += 1
    // Skip whitespace after '='
    while (i < text.length && " \t\n\r".contains(text.charAt(i))) {
      i += 1
    }
    // Check if next token matches the expected token exactly
    if (i >= text.length || !isIdentifierStart(text.charAt(i))) return false

    val identStart = i
    while (i < text.length && isIdentifierPart(text.charAt(i))) {
      i += 1
    }
    val nextToken = text.substring(identStart, i)

    nextToken == expectedToken
  }

  private def makeToken(tpe: TokenType): ProtobufToken = {
    val lexeme = text.substring(start, pos)
    ProtobufToken(tpe, start, pos, lexeme, filepath)
  }
}
