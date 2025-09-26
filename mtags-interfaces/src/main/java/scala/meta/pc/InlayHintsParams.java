package scala.meta.pc;

import java.util.List;
import org.eclipse.lsp4j.Range;

/**
 * Parameters for inlay hint request at a given range in a single source file
 * 
 */
public interface InlayHintsParams extends RangeParams {
  
  /**
   * Response should contain missing type annotations parameters.
   */
  boolean inferredTypes();

  /**
   * Response should contain inferred type parameters.
   */
  boolean typeParameters();

  /**
   * Response should contain decorations for implicit parameters.
   */
  boolean implicitParameters();

  /**
   * Response should contain decorations for by name parameters.
   */
  default boolean byNameParameters() {
    return false;
  }

  /**
   * Response should contain decorations for implicit conversions.
   */
  boolean implicitConversions();

  /**
   * Response should contain decorations for named parameters.
   */
  default boolean namedParameters(){
    return false;
  }

  /**
   * Response should contain intermediate types on transformation chains
   */
  default boolean hintsXRayMode(){
    return false;
  }

  /**
   * Response should contain decorations in pattern matches.
   */
  default boolean hintsInPatternMatch() {
    return false;
  }

  /**
   * Response should contain decorations for closing labels.
   */
  default boolean closingLabels() {
    return false;
  }

}