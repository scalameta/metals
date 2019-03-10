package scala.meta.pc;

import org.eclipse.lsp4j.jsonrpc.CancelChecker;

import java.util.concurrent.CompletionStage;

/**
 * Extension of CancelChecker that supports registering a callback on cancellation.
 *
 * This extension was needed to interrupt the presentation compiler thread to stop
 * expensive typechecking operations.
 */
public interface CancelToken extends CancelChecker {

    /**
     * Completes to true when request has been cancelled, and completes
     * to false when request will never be cancelled. May never complete.
     */
    CompletionStage<Boolean> onCancel();

}
