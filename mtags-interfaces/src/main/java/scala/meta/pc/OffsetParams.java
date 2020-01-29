package scala.meta.pc;

import java.net.URI;

/**
 * Parameters for a presentation compiler request at a given offset in a single source file.
 */
public interface OffsetParams {

    /**
     * The name of the source file.
     */
    String filename();

    /**
     * The text contents of the source file.
     */
    String text();

    /**
     * The character offset of the request.
     */
    int offset();

    /**
     * The cancelation token for this request.
     */
    CancelToken token();

    default void checkCanceled() {
        token().checkCanceled();
    }
}
