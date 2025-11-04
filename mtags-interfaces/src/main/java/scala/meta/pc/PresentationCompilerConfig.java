package scala.meta.pc;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Configuration options used by the Metals presentation compiler. */
public interface PresentationCompilerConfig {

  /**
   * Command ID to trigger parameter hints (textDocument/signatureHelp) in the editor.
   *
   * <p>See
   * https://scalameta.org/metals/docs/integrations/new-editor.html#compileroptionsparameterhintscommand
   * for details.
   */
  Optional<String> parameterHintsCommand();

  /** Command ID to trigger completions (textDocument/completion) in the editor. */
  Optional<String> completionCommand();

  Map<String, String> symbolPrefixes();

  static Map<String, String> defaultSymbolPrefixes() {
    HashMap<String, String> map = new HashMap<>();
    map.put("scala/collection/mutable/", "mutable.");
    map.put("java/util/", "ju.");
    return map;
  }

  /** Returns true if user didn't modify symbol prefixes */
  boolean isDefaultSymbolPrefixes();

  /** What text format to use for rendering `override def` labels for completion items. */
  OverrideDefFormat overrideDefFormat();

  enum OverrideDefFormat {
    /** Render as "override def". */
    Ascii,
    /** Render as "🔼". */
    Unicode
  }

  /** Returns true if the <code>CompletionItem.detail</code> field should be populated. */
  boolean isCompletionItemDetailEnabled();

  /**
   * Returns false if the <code>UserConfiguration.enableStripMarginOnTypeFormatting</code> is
   * configured to false.
   */
  boolean isStripMarginOnTypeFormattingEnabled();

  /** Returns true if the <code>CompletionItem.documentation</code> field should be populated. */
  boolean isCompletionItemDocumentationEnabled();

  /** Returns true if the result from <code>textDocument/hover</code> should include docstrings. */
  boolean isHoverDocumentationEnabled();

  /**
   * True if the client defaults to adding the identation of the reference line that the operation
   * started on (relevant for multiline textEdits)
   */
  boolean snippetAutoIndent();

  /** Returns true if the <code>SignatureHelp.documentation</code> field should be populated. */
  boolean isSignatureHelpDocumentationEnabled();

  /** Returns true if completions can contain snippets. */
  boolean isCompletionSnippetsEnabled();

  /** Returns true if the completion's description should be included in the label. */
  default boolean isDetailIncludedInLabel() {
    return true;
  }

  /**
   * The maximum delay for requests to respond.
   *
   * <p>After the given delay, every request to completions/hover/signatureHelp is automatically
   * cancelled and the presentation compiler is restarted.
   */
  long timeoutDelay();

  /** The unit to use for <code>timeoutDelay</code>. */
  TimeUnit timeoutUnit();

  /**
   * The SemanticDB compiler options to use for the <code>semanticdbTextDocument</code> method.
   *
   * <p>The full list of supported options is documented here
   * https://scalameta.org/docs/semanticdb/guide.html#scalac-compiler-plugin
   */
  List<String> semanticdbCompilerOptions();

  static List<String> defaultSemanticdbCompilerOptions() {
    return Arrays.asList("-P:semanticdb:synthetics:on", "-P:semanticdb:text:on");
  }

  default ContentType hoverContentType() {
    return ContentType.MARKDOWN;
  }

  default boolean emitDiagnostics() {
    return false;
  }

  default Path workspaceRoot() {
    return Paths.get(System.getProperty("user.dir"));
  }

  /** Returns the mode of the source path to use. */
  default SourcePathMode sourcePathMode() {
    return SourcePathMode.PRUNED;
  }
}
