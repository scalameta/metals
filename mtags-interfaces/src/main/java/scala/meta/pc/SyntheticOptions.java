package scala.meta.pc;

import java.util.List;
import org.eclipse.lsp4j.Range;


public interface SyntheticOptions {
  boolean inferredType();
  List<Range> missingTypes();
  boolean implicitParams();
  boolean implicitConversions();
}