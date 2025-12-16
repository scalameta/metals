package scala.meta.pc;

import java.net.URI;
import java.util.List;
import javax.tools.JavaFileObject;

public interface SemanticdbCompilationUnit extends JavaFileObject {

  Language language();

  String binaryName();

  List<String> packageSymbols();

  List<String> toplevelSymbols();

  URI uri();

  String text();
}
