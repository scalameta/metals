package scala.meta.pc;

import java.util.List;

public interface SymbolDocumentation {
    String symbol();
    String name();
    String docstring();
    String defaultValue();
    List<SymbolDocumentation> typeParameters();
    List<SymbolDocumentation> parameters();
}
