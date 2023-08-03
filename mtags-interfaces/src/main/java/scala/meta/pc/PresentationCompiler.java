package scala.meta.pc;

import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.DocumentHighlight;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.Range;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentRangeFormattingParams;
import org.eclipse.lsp4j.DocumentOnTypeFormattingParams;
import org.eclipse.lsp4j.SelectionRange;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.net.URI;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.util.LinkedList;
import java.util.Optional;
import java.util.Properties;
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
	 * Returns token informations from presentation compiler.
	 *
	 */
	public CompletableFuture<List<Node>> semanticTokens(VirtualFileParams params) {
		
		return CompletableFuture.completedFuture(Collections.emptyList());
	}

	/**
	 * Returns code completions for the given source position.
	 *
	 * The returned completion items are incomplete meaning they may not contain
	 * available information such as documentation or the detail signature may have
	 * `x$1` parameter names for Java methods. It's recommended to call
	 * {@link #completionItemResolve(CompletionItem, String)} before displaying the
	 * `detail` and `documentation` fields to the user.
	 *
	 * @implNote supports cancellation.
	 */
	public abstract CompletableFuture<CompletionList> complete(OffsetParams params);

	/**
	 * Returns a fully resolved completion item with defined fields such as
	 * `documentation` and `details` populated.
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
	 * Returns the type of the expression at the given position along with the
	 * symbol of the referenced symbol.
	 */
	public abstract CompletableFuture<Optional<HoverSignature>> hover(OffsetParams params);

	/**
	 * Checks if the symbol at given position can be renamed using presentation
	 * compiler. Returns Some(range) if symbol is defined inside a method or the
	 * file is a worksheet, None otherwise
	 */
	public abstract CompletableFuture<Optional<Range>> prepareRename(OffsetParams params);

	/**
	 * Renames the symbol at given position and all of its occurrences to `name`. If
	 * symbol can't be renamed using presentation compiler (prepareRename returns
	 * None), returns empty list.
	 */
	public abstract CompletableFuture<List<TextEdit>> rename(OffsetParams params, String name);

	/**
	 * Returns the definition of the symbol at the given position.
	 */
	public abstract CompletableFuture<DefinitionResult> definition(OffsetParams params);

	/**
	 * Returns location of the expression's type definition at the given position.
	 */
	public abstract CompletableFuture<DefinitionResult> typeDefinition(OffsetParams params);

	/**
	 * Returns the occurrences of the symbol under the current position in the
	 * entire file.
	 */
	public abstract CompletableFuture<java.util.List<DocumentHighlight>> documentHighlight(OffsetParams params);

	/**
	 * Return decoded and pretty printed TASTy content for .scala or .tasty file.
	 * 
	 */
	public abstract CompletableFuture<String> getTasty(URI targetUri, boolean isHttpEnabled);

	/**
	 * Return the necessary imports for a symbol at the given position.
	 */
	public abstract CompletableFuture<List<AutoImportsResult>> autoImports(String name, OffsetParams params,
			Boolean isExtension);

	/**
	 * Return the missing implements and imports for the symbol at the given
	 * position.
	 */
	public abstract CompletableFuture<List<TextEdit>> implementAbstractMembers(OffsetParams params);

	/**
	 * Return the missing implements and imports for the symbol at the given
	 * position.
	 */
	public abstract CompletableFuture<List<TextEdit>> insertInferredType(OffsetParams params);

	/**
	 * Return the text edits for inlining a value.
	 */
	public CompletableFuture<List<TextEdit>> inlineValue(OffsetParams params) {
		return CompletableFuture.supplyAsync(() -> {
			throw new DisplayableException("Inline value is not available in this version of Scala");
		});
	}

	/**
	 * Extract method in selected range
	 * 
	 * @param range         range to extract from
	 * @param extractionPos position in file to extract to
	 */
	public abstract CompletableFuture<List<TextEdit>> extractMethod(RangeParams range, OffsetParams extractionPos);

	/**
	 * Return named arguments for the apply method that encloses the given position.
	 * May fail with a DisplayableException.
	 */
	public abstract CompletableFuture<List<TextEdit>> convertToNamedArguments(OffsetParams params,
			List<Integer> argIndices);

	/**
	 * The text contents of the fiven file changed.
	 */
	public abstract CompletableFuture<List<Diagnostic>> didChange(VirtualFileParams params);

	/**
	 * File was closed.
	 */
	public abstract void didClose(URI uri);

	/**
	 * Returns the Protobuf byte array representation of a SemanticDB
	 * <code>TextDocument</code> for the given source.
	 */
	public abstract CompletableFuture<byte[]> semanticdbTextDocument(URI filename, String code);

	/**
	 * Return the selections ranges for the given positions.
	 */
	public abstract CompletableFuture<List<SelectionRange>> selectionRange(List<OffsetParams> params);

	// =================================
	// Configuration and lifecycle APIs.
	// =================================

	/**
	 * Clean up resources and shutdown the presentation compiler.
	 *
	 * It is necessary to call this method in order to for example stop the
	 * presentation compiler thread. If this method is not called, then the JVM may
	 * not shut exit cleanly.
	 *
	 * This presentation compiler instance should no longer be used after calling
	 * this method.
	 */
	public abstract void shutdown();

	/**
	 * Clean the symbol table and other mutable state in the compiler.
	 */
	public abstract void restart();

	/**
	 * Set logger level for reports.
	 */
	public PresentationCompiler withReportsLoggerLevel(String level) {
		return this;
	};

	/**
	 * Provide a SymbolSearch to extract docstrings, java parameter names and Scala
	 * default parameter values.
	 */
	public abstract PresentationCompiler withSearch(SymbolSearch search);

	/**
	 * Provide a custom executor service to run asynchronous cancellation or
	 * requests.
	 */
	public abstract PresentationCompiler withExecutorService(ExecutorService executorService);

	/**
	 * Provide a custom scheduled executor service to schedule `Thread.stop()` for
	 * unresponsive compiler instances.
	 */
	public abstract PresentationCompiler withScheduledExecutorService(
			ScheduledExecutorService scheduledExecutorService);

	/**
	 * Provide custom configuration for features like signature help and
	 * completions.
	 */
	public abstract PresentationCompiler withConfiguration(PresentationCompilerConfig config);

	/**
	 * Provide workspace root for features like ammonite script $file completions.
	 */
	public abstract PresentationCompiler withWorkspace(Path workspace);

	/**
	 * Construct a new presentation compiler with the given parameters.
	 *
	 * @param buildTargetIdentifier the build target containing this source file.
	 *                              This is needed for
	 *                              {@link #completionItemResolve(CompletionItem, String)}.
	 * @param classpath             the classpath of this build target.
	 * @param options               the compiler flags for the new compiler.
	 *                              Important, it is recommended to disable all
	 *                              compiler plugins excluding
	 *                              org.scalamacros:paradise, kind-projector and
	 *                              better-monadic-for.
	 */
	public abstract PresentationCompiler newInstance(String buildTargetIdentifier, List<Path> classpath,
		List<String> options);

	// =============================
	// Intentionally missing methods
	// =============================

	// Metals uses diagnostics from the build. It is not on the roadmap to publish
	// diagnostics
	// from the presentation compiler because they are unreliable, especially for
	// long-running
	// compiler instances.
	// def diagnostics(): List[Diagnostics]

	// The presentation compiler does not have enough information to implement it
	// standalone.
	// def definition(params: OffsetParams): List[Location]

	// The presentation compiler does not have enough information to implement it
	// standalone.
	// def references(params: OffsetParams): List[Location]

	// ==============================================
	// Internal methods - not intended for public use
	// ==============================================
	public abstract List<String> diagnosticsForDebuggingPurposes();

	/**
	 * Returns false if the presentation compiler has not been used since the last
	 * reset.
	 * 
	 * NOTE(olafur) This method was added for testing purposes. It's critical that
	 * we correctly reset the presentation compiler when build compilation complete
	 * to prevent the presentatin compiler from returning stale results. It's
	 * difficult to reliably test that a compiler is not returning stale results so
	 * we test instead against the implementation detail that the compiler is `null`
	 * when it has been reset.
	 */
	public abstract boolean isLoaded();

	/**
	 * Scala version for the current presentation compiler
	 */
	public abstract String scalaVersion();

}
