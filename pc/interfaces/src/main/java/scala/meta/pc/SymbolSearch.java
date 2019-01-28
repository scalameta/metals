package scala.meta.pc;

public interface SymbolSearch {
    Result search(String query,
                  String buildTargetIdentifier,
                  SymbolSearchVisitor visitor);
    enum Result {
        COMPLETE,
        INCOMPLETE
    }
}
