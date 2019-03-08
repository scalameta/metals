package scala.meta.pc;

import java.util.Optional;

/**
 * The interface for the presentation compiler to extract symbol documentation and perform fuzzy symbol search.
 */
public interface SymbolSearch {

    /**
     * Returns the documentation of this symbol, if any.
     */
    Optional<SymbolDocumentation> documentation(String symbol);

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
    enum Result {
        COMPLETE,
        INCOMPLETE
    }
}
