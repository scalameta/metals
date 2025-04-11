package scala.meta.pc;

import java.util.List;
import java.util.Optional;
import org.eclipse.lsp4j.SymbolKind;

public interface InspectResult {
  public String symbol();
  public SymbolKind kind();
  public String resultType();
  public String visibility();
  public List<InspectResultParamsList> paramss();
  public List<InspectResult> members();
  public Optional<SymbolDocumentation> docstring();
}
