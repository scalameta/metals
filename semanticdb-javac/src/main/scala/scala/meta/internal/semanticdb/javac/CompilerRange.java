package scala.meta.internal.semanticdb.javac;

/**
 * Describes how to convert a compiler position into SemanticDB Range.
 *
 * <p>A Java compiler position has tree parts: "start", "point" and "end".
 *
 * <pre>
 *     public static void main(String[] args) { }
 *     ^ start
 *                        ^ point (aka. "preferred position")
 *                                              ^ end
 * </pre>
 *
 * A SemanticDB range has four parts: "startLine", "startCharacter", "endLine", "endCharacter".
 */
public enum CompilerRange {
  /** Map the compiler start/end positions to SemanticDB start/end positions. */
  FROM_START_TO_END,

  /**
   * Map the compiler point position to SemanticDB end and use (point - symbol name length) for the
   * SemanticDB start position.
   */
  FROM_END_TO_SYMBOL_NAME,

  /**
   * Map the compiler point position to SemanticDB start and use (point + symbol name length) for
   * the SemanticDB end position.
   */
  FROM_POINT_TO_SYMBOL_NAME,

  /**
   * Map the compiler (point + 1) position to SemanticDB start and use (point + symbol name length +
   * 1) for the SemanticDB end position.
   */
  FROM_POINT_TO_SYMBOL_NAME_PLUS_ONE,

  /**
   * Use text search to find the start of the symbol name and use (found start + symbol name length)
   * for the SemanticDB end position.
   */
  FROM_TEXT_SEARCH,

  /**
   * Use text search to find the start of the symbol name, using the point position as the starting
   * search offset and using (found start + symbol name length) for the SemanticDB end position.
   */
  FROM_POINT_WITH_TEXT_SEARCH,

  /**
   * Use text search to find the start of the symbol name, searching from the end instead of the
   * start.
   */
  FROM_END_WITH_TEXT_SEARCH;

  public boolean isFromPoint() {
    switch (this) {
      case FROM_POINT_TO_SYMBOL_NAME:
      case FROM_POINT_TO_SYMBOL_NAME_PLUS_ONE:
      case FROM_POINT_WITH_TEXT_SEARCH:
        return true;
      default:
        return false;
    }
  }

  public boolean isFromEndPoint() {
    switch (this) {
      case FROM_END_TO_SYMBOL_NAME:
      case FROM_END_WITH_TEXT_SEARCH:
        return true;
      default:
        return false;
    }
  }

  public boolean isFromTextSearch() {
    switch (this) {
      case FROM_TEXT_SEARCH:
      case FROM_END_WITH_TEXT_SEARCH:
      case FROM_POINT_WITH_TEXT_SEARCH:
        return true;
      default:
        return false;
    }
  }

  public boolean isPlusOne() {
    switch (this) {
      case FROM_POINT_TO_SYMBOL_NAME_PLUS_ONE:
        return true;
      default:
        return false;
    }
  }

  public boolean isFromEnd() {
    return this == CompilerRange.FROM_END_WITH_TEXT_SEARCH;
  }
}
