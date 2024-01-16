package scala.meta.pc;

import java.util.List;

public interface PcSymbolInformation {
  String symbol();
  String kindString();
  List<String> parentsList();
  String dealisedSymbol();
  String classOwnerString();
  List<String> overriddenList();
  List<String> propertiesList();
}
