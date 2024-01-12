package scala.meta.pc;

import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.InlayHintLabelPart;
import java.util.List;


public interface SyntheticDecoration{
  Range range();
  String label();
  int kind();
}