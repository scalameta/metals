package scala.meta.pc;

import java.util.List;

/**
 * Documentation about a single symbol such as a class/method/parameter.
 */
public interface SymbolDocumentation {

    /**
     * The SemanticDB string representation of this symbol.
     */
    String symbol();

    /**
     * The human-readable name of this symbol, does not for example include backticks for identifiers with spaces.
     */
    String displayName();

    /**
     * The markdown-rendered documentation of this symbol, or empty string if not found.
     */
    String docstring();

    /**
     * The Scala default parameter value, or empty if not found or not applicable to this symbol.
     *
     * For example, for `def sliding(n: Int = 2)` the default value is `2` for the `n` parameter.
     */
    String defaultValue();

    /**
     * The type parameters of this class or method symbol, or empty list if not applicable.
     */
    List<SymbolDocumentation> typeParameters();

    /**
     * The term parameters of this method symbol, or empty list if not applicable.
     *
     * Returns a flattened list of Scala curried methods.
     */
    List<SymbolDocumentation> parameters();
}
