package scala.meta.pc;

import java.util.Optional;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.Range;

public interface HoverSignature {
  Hover toLsp();

  Optional<String> signature();

  Optional<Range> getRange();

  HoverSignature withRange(Range range);

  default ContentType contentType() {
    return ContentType.MARKDOWN;
  }
}
