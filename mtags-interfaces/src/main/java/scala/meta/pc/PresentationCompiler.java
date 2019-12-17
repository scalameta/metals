package scala.meta.pc;

import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.SignatureHelp;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

/**
 * The public API of the presentation compiler.
 *
 * This API should remain
 */
public abstract class PresentationCompiler {

    // ==============================
    // Language Server Protocol APIs.
    // ==============================

    /**
     * Returns code completions for the given source position.
     *
     * The returned completion items are incomplete meaning they may not contain available
     * information such as documentation or the detail signature may have `x$1` parameter
     * names for Java methods. It's recommended to call
     * {@link #completionItemResolve(CompletionItem, String)}
     * before displaying the `detail` and `documentation` fields to the user.
     *
     * @implNote supports cancellation.
     */
    public abstract CompletableFuture<CompletionList> complete(OffsetParams params);

    /**
     * Returns a fully resolved completion item with defined fields such as `documentation` and `details` populated.
     *
     * @implNote does not support cancellation.
     */
    public abstract CompletableFuture<CompletionItem> completionItemResolve(CompletionItem item, String symbol);

    /**
     * Returns the parameter hints at the given source position.
     *
     * @implNote supports cancellation.
     */
    public abstract CompletableFuture<SignatureHelp> signatureHelp(OffsetParams params);

    /**
     * Returns the type of the expression at the given position along with the symbol of the referenced symbol.
     */
    public abstract CompletableFuture<Optional<Hover>> hover(OffsetParams params);

    /**
     * Returns location of the expression's type definition at the given position.
     */
    public abstract CompletableFuture<List<Location>> typeDefinition(OffsetParams params);

    /**
     * Returns the definition of the symbol at the given position.

     */
    public abstract CompletableFuture<DefinitionResult> definition(OffsetParams params);

    /**
     * Return the necessary imports for a symbol at the given position.
     */
    public abstract CompletableFuture<List<AutoImportsResult>> autoImports(String name, OffsetParams params);

    /**
     * Returns the Protobuf byte array representation of a SemanticDB <code>TextDocument</code> for the given source.
     */
    public abstract CompletableFuture<byte[]> semanticdbTextDocument(String filename, String code);

    // =================================
    // Configuration and lifecycle APIs.
    // =================================

    /**
     * Clean up resources and shutdown the presentation compiler.
     *
     * It is necessary to call this method in order to for example stop the presentation compiler thread.
     * If this method is not called, then the JVM may not shut exit cleanly.
     *
     * This presentation compiler instance should no longer be used after calling this method.
     */
    public abstract void shutdown();

    /**
     * Clean the symbol table and other mutable state in the compiler.
     */
    public abstract void restart();

    /**
     * Provide a SymbolSearch to extract docstrings, java parameter names and Scala default parameter values.
     */
    public abstract PresentationCompiler withSearch(SymbolSearch search);

    /**
     * Provide a custom executor service to run asynchronous cancellation or requests.
     */
    public abstract PresentationCompiler withExecutorService(ExecutorService executorService);

    /**
     * Provide a custom scheduled executor service to schedule `Thread.stop()` for unresponsive compiler instances.
     */
    public abstract PresentationCompiler withScheduledExecutorService(ScheduledExecutorService scheduledExecutorService);

    /**
     * Provide custom configuration for features like signature help and completions.
     */
    public abstract PresentationCompiler withConfiguration(PresentationCompilerConfig config);

    /**
     * Construct a new presentation compiler with the given parameters.
     *
     * @param buildTargetIdentifier the build target containing this source file. This is needed for
     * {@link #completionItemResolve(CompletionItem, String)}.
     * @param classpath the classpath of this build target.
     * @param options the compiler flags for the new compiler. Important, it is recommended to disable
     *                all compiler plugins excluding org.scalamacros:paradise, kind-projector and better-monadic-for.
     */
    public abstract PresentationCompiler newInstance(String buildTargetIdentifier, List<Path> classpath, List<String> options);


    // =============================
    // Intentionally missing methods
    // =============================

    // Metals uses diagnostics from the build. It is not on the roadmap to publish diagnostics
    // from the presentation compiler because they are unreliable, especially for long-running
    // compiler instances.
    // def diagnostics(): List[Diagnostics]

    // The presentation compiler does not have enough information to implement it standalone.
    // def definition(params: OffsetParams): List[Location]

    // The presentation compiler does not have enough information to implement it standalone.
    // def references(params: OffsetParams): List[Location]

    // ==============================================
    // Internal methods - not intended for public use
    // ==============================================
    public abstract List<String> diagnosticsForDebuggingPurposes();
}