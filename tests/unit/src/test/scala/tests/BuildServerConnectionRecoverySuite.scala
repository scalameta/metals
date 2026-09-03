package tests

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future

import scala.meta.internal.metals.BloopServers
import scala.meta.internal.metals.BuildServerConnection.RecoverConnectAction

/**
 * Unit tests for the wedged-build-server recovery in
 * `BuildServerConnection.fromSockets` and `BloopServers` (see issue #3146):
 * the decision that restarts the server once on the first connection failure
 * (and never more, so we don't thrash), and the stop-and-wait helper that must
 * never hang recovery, no matter how the server misbehaves.
 */
class BuildServerConnectionRecoverySuite extends BaseSuite {

  // A real thread pool, not `munitExecutionContext`: the latter runs `Future`
  // bodies inline on the test thread, which would deadlock the tests below
  // that block inside a `Future` until the code under test unblocks them.
  implicit val ec: ExecutionContextExecutorService =
    ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())
  private val scheduler = Executors.newSingleThreadScheduledExecutor()

  override def afterAll(): Unit = {
    scheduler.shutdownNow()
    ec.shutdownNow()
    super.afterAll()
  }

  test("stop-wedged-server-stops") {
    // The healthy path: exit brings the server down, the wait observes it.
    val running = new AtomicBoolean(true)
    BloopServers
      .stopWedgedServer(
        exitServer = () => running.set(false),
        isServerRunning = () => running.get(),
        scheduler,
        timeoutMs = 5000,
      )
      .map(_ => assert(!running.get()))
  }

  test("stop-wedged-server-hanging-exit") {
    // A truly hung server can block `ng-stop` forever; recovery must still
    // complete once the timeout passes.
    val hangForever = new CountDownLatch(1)
    BloopServers
      .stopWedgedServer(
        exitServer = () => hangForever.await(),
        isServerRunning = () => true,
        scheduler,
        timeoutMs = 200,
      )
      .map(_ => ())
  }

  test("stop-wedged-server-broken-check") {
    // A failing liveness check must complete recovery, not hang it.
    val checks = new AtomicBoolean(false)
    BloopServers
      .stopWedgedServer(
        exitServer = () => (),
        isServerRunning = () => {
          checks.set(true)
          throw new IOException("daemon socket gone")
        },
        scheduler,
        timeoutMs = 5000,
      )
      .map(_ => assert(checks.get()))
  }

  test("stop-wedged-server-stops-eventually") {
    // The server takes a few polls to go down after exit; the wait keeps
    // polling instead of giving up on the first still-running check.
    val running = new AtomicBoolean(true)
    val exited = new CountDownLatch(1)
    Future {
      exited.await()
      Thread.sleep(300)
      running.set(false)
    }
    BloopServers
      .stopWedgedServer(
        exitServer = () => exited.countDown(),
        isServerRunning = () => running.get(),
        scheduler,
        timeoutMs = 5000,
      )
      .map(_ => assert(!running.get()))
  }

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
