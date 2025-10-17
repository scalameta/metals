package scala.meta.pc;

import java.net.URI;
import java.util.List;

public interface SemanticdbCompilationUnit {
	String packageSymbol();

	List<String> toplevelSymbols();

	URI uri();

	String text();
}