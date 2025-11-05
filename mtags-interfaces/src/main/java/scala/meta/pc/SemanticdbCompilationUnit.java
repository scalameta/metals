package scala.meta.pc;

import java.net.URI;
import java.util.List;

public interface SemanticdbCompilationUnit {
  Language language();

  String packageSymbol();

  String binaryName();

  List<String> toplevelSymbols();

  URI uri();

  String text();
}
