package scala.meta.pc;

import scala.meta.pc.reports.ReportContext;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.DocumentHighlight;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.CompletionTriggerKind;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.SelectionRange;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The raw public API of the presentation compiler that does not handle synchronisation.
 * Scala compiler can't run concurrent code at that point, so we need to enforce sequential, single threaded execution.
 * It has to be implemented by the consumer of this API.
 *
 * This API should remain binary compatible
 */
public abstract class RawPresentationCompiler {

	// ==============================
	// Language Server Protocol APIs.
	// ==============================

	/**
	 * Returns token informations from presentation compiler.
	 *
	 */
	public abstract List<Node> semanticTokens(VirtualFileParams params);

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
	public abstract CompletionList complete(OffsetParams params, CompletionTriggerKind completionTriggerKind);

	/**
	 * Returns a fully resolved completion item with defined fields such as
	 * `documentation` and `details` populated.
	 *
	 * @implNote does not support cancellation.
	 */
	public abstract CompletionItem completionItemResolve(CompletionItem item, String symbol);

	/**
	 * Returns the parameter hints at the given source position.
	 *
	 * @implNote supports cancellation.
	 */
	public abstract SignatureHelp signatureHelp(OffsetParams params);

	/**
	 * Returns the type of the expression at the given position along with the
	 * symbol of the referenced symbol.
	 */
	public abstract Optional<HoverSignature> hover(OffsetParams params);

	/**
	 * Checks if the symbol at given position can be renamed using presentation
	 * compiler. Returns Some(range) if symbol is defined inside a method or the
	 * file is a worksheet, None otherwise
	 */
	public abstract Optional<Range> prepareRename(OffsetParams params);

	/**
	 * Renames the symbol at given position and all of its occurrences to `name`. If
	 * symbol can't be renamed using presentation compiler (prepareRename returns
	 * None), returns empty list.
	 */
	public abstract List<TextEdit> rename(OffsetParams params, String name);

	/**
	 * Returns the definition of the symbol at the given position.
	 */
	public abstract DefinitionResult definition(OffsetParams params);

	/**
	 * Returns location of the expression's type definition at the given position.
	 */
	public abstract DefinitionResult typeDefinition(OffsetParams params);

	/**
	 * Returns the occurrences of the symbol under the current position in the
	 * entire file.
	 */
	public abstract List<DocumentHighlight> documentHighlight(OffsetParams params);

	/**
	 * Returns the references of the symbol under the current position in the target files.
	 */
	public abstract List<ReferencesResult> references(ReferencesRequest params);

	/**
	 * Execute the given code action
	 */
	public abstract <T> List<TextEdit> codeAction(OffsetParams params, String codeActionId,
			Optional<T> codeActionPayload);

	/**
	 * Returns the list of code actions supported by the current presentation compiler.
	 */
	public abstract List<String> supportedCodeActions();

	/**
	 * Return decoded and pretty printed TASTy content for .scala or .tasty file.
	 *
	 */
	public abstract String getTasty(URI targetUri, boolean isHttpEnabled);

	/**
	 * Return the necessary imports for a symbol at the given position.
	 */
	public abstract List<AutoImportsResult> autoImports(String name, OffsetParams params,
			Boolean isExtension);

	/**
	 * Return the missing implements and imports for the symbol at the given
	 * position.
	 */
	public abstract List<TextEdit> implementAbstractMembers(OffsetParams params);

	/**
	 * Return the missing implements and imports for the symbol at the given
	 * position.
	 */
	public abstract List<TextEdit> insertInferredType(OffsetParams params);

	/**
	 * Return the text edits for inlining a value.
	 */
	public abstract List<TextEdit> inlineValue(OffsetParams params);

	/**
	 * Extract method in selected range
	 *
	 * @param range         range to extract from
	 * @param extractionPos position in file to extract to
	 */
	public abstract List<TextEdit> extractMethod(RangeParams range, OffsetParams extractionPos);

	/**
	 * Return named arguments for the apply method that encloses the given position.
	 * May fail with a DisplayableException.
	 */
	public abstract List<TextEdit> convertToNamedArguments(OffsetParams params,
			List<Integer> argIndices);

	/**
	 * The text contents of the given file changed.
	 */
	public abstract List<Diagnostic> didChange(VirtualFileParams params);

	/**
	 * Returns decorations for missing type adnotations, inferred type parameters,
	 * implicit parameters and conversions.
	 */
	public abstract List<InlayHint> inlayHints(InlayHintsParams params);

	public abstract Optional<PcSymbolInformation> info(String symbol);

	/**
	 * File was closed.
	 */
	public abstract void didClose(URI uri);

	/**
	 * Returns the Protobuf byte array representation of a SemanticDB
	 * <code>TextDocument</code> for the given source.
	 */
	public abstract byte[] semanticdbTextDocument(URI filename, String code);

	/**
	 * Returns the Protobuf byte array representation of a SemanticDB
	 * <code>TextDocument</code> for the given source.
	 */
	public abstract byte[] semanticdbTextDocument(VirtualFileParams params);

	/**
	 * Return the selections ranges for the given positions.
	 */
	public abstract List<SelectionRange> selectionRange(List<OffsetParams> params);

	// =================================
	// Configuration and lifecycle APIs.
	// =================================

	/**
	 * Set logger level for reports.
	 */
	public abstract RawPresentationCompiler withReportsLoggerLevel(String level);

	/**
	 * Set build target name.
	 */
	public abstract RawPresentationCompiler withBuildTargetName(String buildTargetName);

	/**
	 * Provide a SymbolSearch to extract docstrings, java parameter names and Scala
	 * default parameter values.
	 */
	public abstract RawPresentationCompiler withSearch(SymbolSearch search);

	/**
	 * Provide custom configuration for features like signature help and
	 * completions.
	 */
	public abstract RawPresentationCompiler withConfiguration(PresentationCompilerConfig config);

	/**
	 * Provide workspace root for features like ammonite script $file completions.
	 */
	public abstract RawPresentationCompiler withWorkspace(Path workspace);

	/**
	 * Provide CompletionItemPriority for additional sorting completion items.
	 */
	public abstract RawPresentationCompiler withCompletionItemPriority(CompletionItemPriority priority);

	/**
	 * Provide a reporting context for reporting errors.
	 */
	public abstract RawPresentationCompiler withReportContext(ReportContext reportContext);

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
	public abstract RawPresentationCompiler newInstance(String buildTargetIdentifier, List<Path> classpath,
			List<String> options);

	/**
	 * Scala version for the current presentation compiler
	 */
	public abstract String scalaVersion();

	public abstract String buildTargetId();

}
