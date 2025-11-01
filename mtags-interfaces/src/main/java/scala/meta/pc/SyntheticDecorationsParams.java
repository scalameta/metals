package scala.meta.pc;


import java.util.List;
import org.eclipse.lsp4j.Range;
/**
 * Parameters for inlay hint request at a given range in a single source file
 * 
 */
public interface SyntheticDecorationsParams extends VirtualFileParams {

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
   * Response should contain decorations for implicit conversions.
   */
  boolean implicitConversions();
  /**
   * Response should contain decorations for closing labels.
   */
  default boolean closingLabels() {
    return false;
  };
}