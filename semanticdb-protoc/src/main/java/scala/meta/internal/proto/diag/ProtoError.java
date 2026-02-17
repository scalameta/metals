package scala.meta.internal.proto.diag;

/** Represents a parsing or semantic error in a protobuf file. */
public final class ProtoError extends RuntimeException {

  private final String path;
  private final int line;
  private final int column;
  private final String rawMessage;

  public ProtoError(String path, int line, int column, String message) {
    super(formatMessage(path, line, column, message));
    this.path = path;
    this.line = line;
    this.column = column;
    this.rawMessage = message;
  }

  public String path() {
    return path;
  }

  public int line() {
    return line;
  }

  public int column() {
    return column;
  }

  public String rawMessage() {
    return rawMessage;
  }

  private static String formatMessage(String path, int line, int column, String message) {
    return String.format("%s:%d:%d: %s", path, line + 1, column + 1, message);
  }
}
