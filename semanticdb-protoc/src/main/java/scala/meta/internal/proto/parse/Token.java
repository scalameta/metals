package scala.meta.internal.proto.parse;

/**
 * Represents a token from the protobuf tokenizer.
 *
 * <p>Following protoc's tokenizer design: - Keywords are handled at the parser level, not lexer
 * level - All keywords are scanned as TYPE_IDENTIFIER - Symbols are single-character tokens
 */
public final class Token {

  public enum Type {
    /** Next() has not yet been called. */
    TYPE_START,
    /** End of input reached. */
    TYPE_END,
    /** A sequence of letters, digits, and underscores, not starting with a digit. */
    TYPE_IDENTIFIER,
    /** A sequence of digits representing an integer (decimal, hex with 0x, or octal). */
    TYPE_INTEGER,
    /** A floating point literal with fractional part and/or exponent. */
    TYPE_FLOAT,
    /** A quoted sequence of escaped characters (single or double quotes). */
    TYPE_STRING,
    /** Any other printable character like '{', '}', ';', etc. Single character. */
    TYPE_SYMBOL
  }

  private final Type type;
  private final String text;
  private final int startOffset;
  private final int endOffset;
  private final int line;
  private final int column;

  public Token(Type type, String text, int startOffset, int endOffset, int line, int column) {
    this.type = type;
    this.text = text;
    this.startOffset = startOffset;
    this.endOffset = endOffset;
    this.line = line;
    this.column = column;
  }

  public Type type() {
    return type;
  }

  public String text() {
    return text;
  }

  public int startOffset() {
    return startOffset;
  }

  public int endOffset() {
    return endOffset;
  }

  public int line() {
    return line;
  }

  public int column() {
    return column;
  }

  /** Creates a start token. */
  public static Token start() {
    return new Token(Type.TYPE_START, "", 0, 0, 0, 0);
  }

  /** Creates an end token at the given position. */
  public static Token end(int offset, int line, int column) {
    return new Token(Type.TYPE_END, "", offset, offset, line, column);
  }

  @Override
  public String toString() {
    return String.format("Token(%s, \"%s\", [%d:%d])", type, text, line, column);
  }
}
