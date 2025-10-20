package scala.meta.pc;

import java.util.List;
import java.util.Optional;
import org.eclipse.lsp4j.TextEdit;

public interface AutoImportsResult {
  public String packageName();
  public List<TextEdit> edits();
  default public Optional<String> symbol() {
    return Optional.empty();
  };
}
