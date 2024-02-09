package scala.meta.pc;

import java.util.List;

public interface PcSymbolInformation {
  String symbol();
  PcSymbolKind kind();
  List<String> parents();
  String dealiasedSymbol();
  String classOwner();
  List<String> overriddenSymbols();
  List<PcSymbolProperty> properties();
}
