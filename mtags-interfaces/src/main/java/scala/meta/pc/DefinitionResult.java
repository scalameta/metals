package scala.meta.pc;

import java.util.List;
import java.util.Collections;
import org.eclipse.lsp4j.Location;

public interface DefinitionResult {
  String symbol();
  List<Location> locations();

  default List<SymbolSource> symbolSource() {
      return Collections.emptyList();
  }
}
