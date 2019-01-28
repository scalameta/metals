package scala.meta.pc;

public interface SymbolIndexer {
    void visit(String symbol, SymbolVisitor visitor);
}
