package scala.meta.pc;

import java.util.List;
import org.eclipse.lsp4j.Range;

/**
 * Parameters for inlay hint request at a given range in a single source file
 * 
 */
public interface SyntheticDecorationsParams extends VirtualFileParams {
  
  /**
   * Ranges of missing type annotations.
   */
  List<Range> declsWithoutTypesRanges();

  /**
   * Response should contain inferred type parameters.
   */
  boolean inferredTypes();

  /**
   * Response should contain decorations for implicit parameters.
   */
  boolean implicitParameters();

  /**
   * Response should contain decorations for implicit conversions.
   */
  boolean implicitConversions();

}