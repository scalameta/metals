package scala.meta.pc;

import java.util.HashMap;
import java.util.Map;
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

    Map<String, String> symbolPrefixes();

    static Map<String, String> defaultSymbolPrefixes() {
        HashMap<String, String> map = new HashMap<>();
        map.put("scala/collection/mutable/", "mutable.");
        map.put("java/util/", "ju.");
        return map;
    }

}
