package scala.meta.pc;

import org.eclipse.lsp4j.Location;
import java.util.List;
import java.util.Optional;
import java.net.URI;

/**
 * The interface for the presentation compiler to extract symbol documentation and perform fuzzy symbol search.
 */
public interface SymbolSearch {

    /**
     * Returns the documentation of this symbol, if any.
     */
    @Deprecated
    Optional<SymbolDocumentation> documentation(String symbol, ParentSymbols parents);

    /**
     * Returns the documentation of this symbol, if any.
     */
    default Optional<SymbolDocumentation> documentation(String symbol, ParentSymbols parents, ContentType contentType) {
        return documentation(symbol, parents);
    }

    /**
     * Returns the definition of this symbol, if any.
     */
    List<Location> definition(String symbol, URI sourceUri);

    /**
     * Returns the all symbols in the file where the given symbol is defined
     * in declaration order, if any.
     */
    List<String> definitionSourceToplevels(String symbol, URI sourceUri);

    /**
     * Runs fuzzy symbol search for the given query.
     *
     * @param query the text query, for example "ArrDeq" that could match "java.util.ArrayDeque".
     * @param buildTargetIdentifier the build target where to perform the search. This parameter
     *                              determines which classpath.
     * @param visitor The visitor that accepts the search results as the come.
     * @return returns Result.COMPLETE if the search results exhaustively covered
     * all possible search results for this query, or Result.INCOMPLETE if there
     * may appear more search results by refining the search query. For example,
     * the query "S" returns only exact matches in the classpath so this methods
     * returns Result.INCOMPLETE to indicate that refining the query to something
     * like "StreamHandler" may produce more search results.
     */
    Result search(String query,
                  String buildTargetIdentifier,
                  SymbolSearchVisitor visitor);
    Result searchMethods(String query,
                  String buildTargetIdentifier,
                  SymbolSearchVisitor visitor);
    
    /**
     * Returns implicit class members that are applicable for the given parameter type.
     * This is used to provide completions for methods from implicit classes.
     * 
     * @param paramTypeSymbol the semanticdb symbol of the parameter type (e.g., "scala/Int#")
     * @return list of implicit class members applicable to this type
     */
    default List<ImplicitClassMemberResult> queryImplicitClassMembers(String paramTypeSymbol) {
        return java.util.Collections.emptyList(); // Default implementation returns empty list
    }
    
    enum Result {
        COMPLETE,
        INCOMPLETE
    }
}
