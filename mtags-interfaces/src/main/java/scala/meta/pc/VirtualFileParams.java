package scala.meta.pc;

import java.net.URI;
import java.util.Optional;

/**
 * Parameters for a presentation compiler request at a given offset in a single source file.
 */
public interface VirtualFileParams {
    /**
     * The name of the source file.
     */
    URI uri();

    /**
     * The text contents of the source file.
     */
    String text();

    /**
     * The cancelation token for this request.
     */
    CancelToken token();

    /**
     * Information about files that changed since last compilation
     * and should be outline compiled.
     */
    default Optional<OutlineFiles> outlineFiles() {
        return Optional.empty();
    }

    default void checkCanceled() {
        token().checkCanceled();
    }
}
