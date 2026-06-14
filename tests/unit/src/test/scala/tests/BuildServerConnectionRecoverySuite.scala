package tests

import java.io.IOException
import java.util.concurrent.TimeoutException

import scala.meta.internal.metals.BuildServerConnection.RecoverConnectAction

/**
 * Unit tests for the build-server reconnection decision used by
 * `BuildServerConnection.fromSockets` (see issue #3146). The decision is what
 * lets Metals recover from a Bloop server that reports itself as running but is
 * actually wedged: the first connection failure restarts the server once, and
 * recovery is never attempted more than once so we don't thrash.
 */
class BuildServerConnectionRecoverySuite extends BaseSuite {

  test("first-failure-recovers") {
    // The first timeout or IO failure triggers a one-shot recovery of a
    // possibly-wedged server before retrying.
    assertEquals(
      RecoverConnectAction(
        new TimeoutException(),
        retriesLeft = 5,
        alreadyRecovered = false,
      ),
      RecoverConnectAction.RecoverAndRetry,
    )
    assertEquals(
      RecoverConnectAction(
        new IOException(),
        retriesLeft = 5,
        alreadyRecovered = false,
      ),
      RecoverConnectAction.RecoverAndRetry,
    )
  }

  test("recovery-is-one-shot") {
    // Once recovery has been spent, timeouts fall back to a plain retry and the
    // server is never restarted again.
    assertEquals(
      RecoverConnectAction(
        new TimeoutException(),
        retriesLeft = 4,
        alreadyRecovered = true,
      ),
      RecoverConnectAction.Retry,
    )
    // An IO failure after recovery means the fresh server is unreachable too, so
    // we stop rather than retry.
    assertEquals(
      RecoverConnectAction(
        new IOException(),
        retriesLeft = 4,
        alreadyRecovered = true,
      ),
      RecoverConnectAction.GiveUp,
    )
  }

  test("give-up-when-retries-exhausted") {
    assertEquals(
      RecoverConnectAction(
        new TimeoutException(),
        retriesLeft = 0,
        alreadyRecovered = false,
      ),
      RecoverConnectAction.GiveUp,
    )
    assertEquals(
      RecoverConnectAction(
        new TimeoutException(),
        retriesLeft = 0,
        alreadyRecovered = true,
      ),
      RecoverConnectAction.GiveUp,
    )
  }

  test("give-up-on-unrelated-errors") {
    // Errors that aren't connection timeouts/IO failures propagate unchanged.
    assertEquals(
      RecoverConnectAction(
        new RuntimeException("boom"),
        retriesLeft = 5,
        alreadyRecovered = false,
      ),
      RecoverConnectAction.GiveUp,
    )
  }
}
