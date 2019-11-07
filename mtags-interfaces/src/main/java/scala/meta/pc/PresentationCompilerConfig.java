package scala.meta.pc;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Configuration options used by the Metals presentation compiler.
 */
public interface PresentationCompilerConfig {

    /**
     * Command ID to trigger parameter hints (textDocument/signatureHelp) in the editor.
     *
     * See https://scalameta.org/metals/docs/editors/new-editor.html#dmetalssignature-helpcommand
     * for details.
     */
    Optional<String> parameterHintsCommand();

    /**
     * Command ID to trigger completions (textDocument/completion) in the editor.
     */
    Optional<String> completionCommand();

    Map<String, String> symbolPrefixes();

    static Map<String, String> defaultSymbolPrefixes() {
        HashMap<String, String> map = new HashMap<>();
        map.put("scala/collection/mutable/", "mutable.");
        map.put("java/util/", "ju.");
        return map;
    }

    /**
     * What text format to use for rendering `override def` labels for completion items.
     */
    OverrideDefFormat overrideDefFormat();

    enum OverrideDefFormat {
        /** Render as "override def". */
        Ascii,
        /** Render as "🔼". */
        Unicode
    }

    /**
     * Returns true if the <code>CompletionItem.detail</code> field should be populated.
     */
    boolean isCompletionItemDetailEnabled();

    /**
     * Returns true if the <code>CompletionItem.documentation</code> field should be populated.
     */
    boolean isCompletionItemDocumentationEnabled();

    /**
     * Returns true if the result from <code>textDocument/hover</code> should include docstrings.
     */
    boolean isHoverDocumentationEnabled();

    /**
     * True if the client defaults to adding the identation of the reference
     * line that the operation started on (relevant for multiline textEdits)
     */
    boolean snippetAutoIndent();

    /**
     * Returns true if the <code>SignatureHelp.documentation</code> field should be populated.
     */
    boolean isSignatureHelpDocumentationEnabled();

    /**
     * The maximum delay for requests to respond.
     *
     * After the given delay, every request to completions/hover/signatureHelp
     * is automatically cancelled and the presentation compiler is restarted.
     */
    long timeoutDelay();

    /**
     * The unit to use for <code>timeoutDelay</code>.
     */
    TimeUnit timeoutUnit();

}
