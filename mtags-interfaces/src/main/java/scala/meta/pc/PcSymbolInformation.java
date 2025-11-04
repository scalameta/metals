package scala.meta.pc;

import java.util.Collections;
import java.util.List;

public interface PcSymbolInformation {
  String symbol();

  PcSymbolKind kind();

  List<String> parents();

  String dealiasedSymbol();

  String classOwner();

  List<String> overriddenSymbols();

  // overloaded methods
  List<String> alternativeSymbols();

  List<PcSymbolProperty> properties();

  default List<String> recursiveParents() {
    return Collections.emptyList();
  }

  default List<String> annotations() {
    return Collections.emptyList();
  }

  default List<String> memberDefsAnnotations() {
    return Collections.emptyList();
  }
}
