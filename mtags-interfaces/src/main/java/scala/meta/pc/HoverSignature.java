package scala.meta.pc;

import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.Hover;

import java.util.Optional;

public interface HoverSignature {
  Hover toLsp();
  Optional<String> signature();
  Optional<Range> getRange();
  HoverSignature withRange(Range range);
}
