package scala.meta.pc;

import java.util.Optional;

/**
 * Configuration options used by the Metals presentation compiler.
 */
public interface PresentationCompilerConfig {

    /**
     * Command ID to trigger parameter hints (textDocument/signatureHelp) in the editor.
     *
     * See https://scalameta.org/metals/docs/editors/new-editor.html#dmetalssignature-help-command
     * for details.
     */
    Optional<String> parameterHintsCommand();

}
