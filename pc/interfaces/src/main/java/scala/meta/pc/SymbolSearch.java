package scala.meta.pc;

import java.util.Optional;

public interface SymbolSearch {

    Optional<SymbolDocumentation> documentation(String symbol);

    Result search(String query,
                  String buildTargetIdentifier,
                  SymbolSearchVisitor visitor);
    enum Result {
        COMPLETE,
        INCOMPLETE
    }
}
