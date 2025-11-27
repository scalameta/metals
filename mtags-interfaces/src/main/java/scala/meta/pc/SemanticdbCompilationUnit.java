package scala.meta.pc;

import java.net.URI;
import java.util.List;

public interface SemanticdbCompilationUnit {
  Language language();

  List<String> packageSymbols();

  String binaryName();

  List<String> toplevelSymbols();

  URI uri();

  String text();
}
