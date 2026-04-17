package scala.meta.internal.proto.parse;

import scala.meta.internal.proto.diag.SourceFile;

/**
 * Tokenizer for protobuf source files.
 *
 * <p>Follows protoc's tokenizer design: - Produces identifiers, integers, floats, strings, and
 * single-character symbols - Keywords are not distinguished at the lexer level - Supports C-style
 * and C++-style comments - Tracks line and column positions for error reporting
 */
public final class Tokenizer {

  private final SourceFile source;
  private final String text;
  private int pos;
  private int line;
  private int column;
  private Token current;
  private Token previous;

  public Tokenizer(SourceFile source) {
    this.source = source;
    this.text = source.content();
    this.pos = 0;
    this.line = 0;
    this.column = 0;
    this.current = Token.start();
    this.previous = Token.start();
  }

  /** Get the current token. */
  public Token current() {
    return current;
  }

  /** Get the previous token. */
  public Token previous() {
    return previous;
  }

  /** Get the source file. */
  public SourceFile source() {
    return source;
  }

  /** Advance to the next token. Returns false if end of input is reached. */
  public boolean next() {
    previous = current;
    skipWhitespaceAndComments();

    if (isAtEnd()) {
      current = Token.end(pos, line, column);
      return false;
    }

    int startOffset = pos;
    int startLine = line;
    int startColumn = column;
    char c = advance();

    Token.Type type;
    String text;

    if (isIdentifierStart(c)) {
      // Identifier
      while (!isAtEnd() && isIdentifierPart(peek())) {
        advance();
      }
      type = Token.Type.TYPE_IDENTIFIER;
      text = this.text.substring(startOffset, pos);
    } else if (c == '0' && !isAtEnd() && (peek() == 'x' || peek() == 'X')) {
      // Hex integer
      advance(); // consume 'x'
      while (!isAtEnd() && isHexDigit(peek())) {
        advance();
      }
      type = Token.Type.TYPE_INTEGER;
      text = this.text.substring(startOffset, pos);
    } else if (isDigit(c)) {
      // Integer or float
      while (!isAtEnd() && isDigit(peek())) {
        advance();
      }

      // Check for floating point
      if (!isAtEnd() && peek() == '.' && (pos + 1 < this.text.length()) && isDigit(peekNext())) {
        advance(); // consume '.'
        while (!isAtEnd() && isDigit(peek())) {
          advance();
        }
        type = Token.Type.TYPE_FLOAT;
      } else if (!isAtEnd() && (peek() == 'e' || peek() == 'E')) {
        // Exponent without decimal point
        advance(); // consume 'e'
        if (!isAtEnd() && (peek() == '+' || peek() == '-')) {
          advance();
        }
        while (!isAtEnd() && isDigit(peek())) {
          advance();
        }
        type = Token.Type.TYPE_FLOAT;
      } else {
        type = Token.Type.TYPE_INTEGER;
      }

      // Check for exponent in float
      if (type == Token.Type.TYPE_FLOAT || (!isAtEnd() && (peek() == 'e' || peek() == 'E'))) {
        if (!isAtEnd() && (peek() == 'e' || peek() == 'E')) {
          advance(); // consume 'e'
          if (!isAtEnd() && (peek() == '+' || peek() == '-')) {
            advance();
          }
          while (!isAtEnd() && isDigit(peek())) {
            advance();
          }
          type = Token.Type.TYPE_FLOAT;
        }
      }

      text = this.text.substring(startOffset, pos);
    } else if (c == '"' || c == '\'') {
      // String literal
      char quote = c;
      StringBuilder sb = new StringBuilder();
      sb.append(c);
      while (!isAtEnd() && peek() != quote && peek() != '\n') {
        char next = advance();
        sb.append(next);
        if (next == '\\' && !isAtEnd()) {
          // Escape sequence
          sb.append(advance());
        }
      }
      if (!isAtEnd() && peek() == quote) {
        sb.append(advance()); // closing quote
      }
      type = Token.Type.TYPE_STRING;
      text = sb.toString();
    } else {
      // Single character symbol
      type = Token.Type.TYPE_SYMBOL;
      text = String.valueOf(c);
    }

    current = new Token(type, text, startOffset, pos, startLine, startColumn);
    return true;
  }

  private void skipWhitespaceAndComments() {
    while (!isAtEnd()) {
      char c = peek();
      if (c == ' ' || c == '\t' || c == '\r') {
        advance();
      } else if (c == '\n') {
        advance();
        line++;
        column = 0;
      } else if (c == '/') {
        if (peekNext() == '/') {
          // Single-line comment
          while (!isAtEnd() && peek() != '\n') {
            advance();
          }
        } else if (peekNext() == '*') {
          // Multi-line comment
          advance(); // consume '/'
          advance(); // consume '*'
          while (!isAtEnd() && !(peek() == '*' && peekNext() == '/')) {
            if (peek() == '\n') {
              line++;
              column = 0;
            }
            advance();
          }
          if (!isAtEnd()) {
            advance(); // consume '*'
            advance(); // consume '/'
          }
        } else {
          return; // Not a comment
        }
      } else {
        return;
      }
    }
  }

  private boolean isAtEnd() {
    return pos >= text.length();
  }

  private char advance() {
    char c = text.charAt(pos);
    pos++;
    column++;
    return c;
  }

  private char peek() {
    return isAtEnd() ? '\0' : text.charAt(pos);
  }

  private char peekNext() {
    return (pos + 1 >= text.length()) ? '\0' : text.charAt(pos + 1);
  }

  private boolean isIdentifierStart(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
  }

  private boolean isIdentifierPart(char c) {
    return isIdentifierStart(c) || isDigit(c);
  }

  private boolean isDigit(char c) {
    return c >= '0' && c <= '9';
  }

  private boolean isHexDigit(char c) {
    return isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
  }
}
