package scala.meta.pc;

import org.eclipse.lsp4j.Range;

public interface SyntheticDecoration {
  Range range();

  String label();

  int kind();
}
