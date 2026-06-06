package scala.meta.internal.metals.debug.tests

import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Promise
import scala.concurrent.TimeoutException
import scala.concurrent.duration._

import scala.meta.internal.metals.debug.DebugProxy

import tests.BaseSuite

/**
 * Regression tests for the debug proxy teardown ordering that fixes
 * https://github.com/scalameta/metals/issues/2043, where output produced right
 * before the JVM exits was dropped from the debug console because the proxy tore
 * down (closing the adapter connection) as soon as the client disconnected,
 * before the trailing output had been drained.
 */
class DebugProxyTeardownSuite extends BaseSuite {
  implicit val ec: ExecutionContext = ExecutionContext.global

  test("client-disconnect-waits-for-server-drain") {
    val server = Promise[Unit]()
    val client = Promise[Unit]()
    val cancels = new AtomicInteger(0)
    val cancelled = Promise[Unit]()

    DebugProxy.teardownOnExit(server.future, client.future, 10.seconds) { () =>
      cancels.incrementAndGet()
      cancelled.trySuccess(())
    }

    // The client (e.g. VS Code) disconnects first, on the `terminated` event.
    client.success(())

    // Teardown must NOT happen while the server is still draining output.
    intercept[TimeoutException](Await.result(cancelled.future, 300.millis))
    assertEquals(cancels.get(), 0)

    // Once the server reaches EOF (all output forwarded), teardown proceeds.
    server.success(())
    Await.result(cancelled.future, 5.seconds)
    assert(cancels.get() >= 1)
  }

  test("server-eof-cancels-immediately") {
    val server = Promise[Unit]()
    val client = Promise[Unit]() // client never disconnects
    val cancelled = Promise[Unit]()

    DebugProxy.teardownOnExit(server.future, client.future, 10.seconds) { () =>
      cancelled.trySuccess(())
    }

    // Server EOF means everything has been read, so teardown is safe right away.
    server.success(())
    Await.result(cancelled.future, 5.seconds)
  }

  test("client-disconnect-cancels-after-timeout-when-server-never-drains") {
    val server = Promise[Unit]() // never completed (e.g. process won't exit)
    val client = Promise[Unit]()
    val cancelled = Promise[Unit]()

    DebugProxy.teardownOnExit(server.future, client.future, 300.millis) { () =>
      cancelled.trySuccess(())
    }

    client.success(())
    // The timeout bounds the wait so teardown still happens.
    Await.result(cancelled.future, 5.seconds)
  }
}
