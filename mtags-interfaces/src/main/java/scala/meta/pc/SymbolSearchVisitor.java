package scala.meta.pc;

import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolKind;

import java.nio.file.Path;

/**
 * Consumer of symbol search results.
 * <p>
 * Search results can come from two different sources: classpath or workspace.
 * Classpath results are symbols defined in library dependencies while workspace
 * results are symbols that are defined by the user.
 */
public abstract class SymbolSearchVisitor {

    /**
     * Whether to visit a classpath package.
     *
     * @param pkg the package name formatted as `scala/collection/mutable/`
     * @return true if this visitor accepts results from this package, false otherwise.
     */
    abstract public boolean shouldVisitPackage(String pkg);

    /**
     * Visit a single classfile from the library dependency classpath.
     *
     * @param pkg      the enclosing package, formatted as `scala/collection/mutable/`.
     * @param filename the filename of the classfile, formatted as `Outer$Inner.class`
     * @return the number of produced results from this classfile.
     */
    abstract public int visitClassfile(String pkg, String filename);

    /**
     * @param path   the source file where the symbol is defined.
     * @param symbol the SemanticDB symbol, formatted as `scala/Predef.String#`
     * @param kind   the kind of this symbol, for example class/interface/trait/method.
     * @param range  the source range location where this symbol is defined.
     * @return the number of produced results from this classfile.
     */
    abstract public int visitWorkspaceSymbol(Path path, String symbol, SymbolKind kind, Range range);

    /**
     * @return returns true if the search has been cancelled, false otherwise.
     */
    abstract public boolean isCancelled();

    public int visitWorkspacePackage(String pkg) {
        return 0;
    }

    /**
     * Visit an implicit class symbol to search for extension methods.
     *
     * @param path   the source file where the implicit class is defined.
     * @param symbol the SemanticDB symbol of the implicit class, formatted as `scala/StringOps#`
     * @param query  the method name query to filter methods by
     * @param range  the source range location where this implicit class is defined.
     * @return the number of produced results from this implicit class's methods.
     */
    public int visitImplicitClassSymbol(Path path, String symbol, String query, Range range) {
        return 0;
    }

}
