package scala.meta.pc;

/** Parameters for inlay hint request at a given range in a single source file */
public interface InlayHintsParams extends RangeParams {

  /** Response should contain missing type annotations parameters. */
  boolean inferredTypes();

  /** Response should contain inferred type parameters. */
  boolean typeParameters();

  /** Response should contain decorations for implicit parameters. */
  boolean implicitParameters();

  /** Response should contain decorations for implicit conversions. */
  boolean implicitConversions();

  /** Response should contain decorations in pattern matches. */
  default boolean hintsInPatternMatch() {
    return false;
  }
}
