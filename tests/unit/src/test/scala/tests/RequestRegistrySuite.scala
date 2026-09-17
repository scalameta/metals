package tests

import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.FiniteDuration

import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.clients.language.NoopLanguageClient
import scala.meta.internal.metals.utils.RequestRegistry
import scala.meta.internal.metals.utils.Timeout

import munit.FunSuite
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.ShowMessageRequestParams

class RequestRegistrySuite extends FunSuite {
  implicit val ex: ExecutionContextExecutorService =
    ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())
  val duration: FiniteDuration = FiniteDuration(1, TimeUnit.SECONDS)
  def createRegistry(
      onTimeout: () => MessageActionItem = () => Messages.RequestTimeout.cancel
  ) =
    new RequestRegistry(
      List(),
      new RequestRegistrySuite.Client(() => Future.successful(onTimeout())),
    )
  val defaultTimeout: Some[Timeout] = Some(Timeout.default(duration))
  val askIfCancelTimeout: Some[Timeout] = Some(Timeout("ask", duration))

  test("avg") {
    val requestRegistry = createRegistry()
    val doneTimeout = Some(Timeout("done", duration))
    for {
      _ <- requestRegistry
        .register(
          ExampleFutures.done,
          doneTimeout,
        )
        .future
      _ <- requestRegistry
        .register(
          ExampleFutures.done,
          doneTimeout,
        )
        .future
      _ <- requestRegistry
        .register(
          ExampleFutures.done,
          doneTimeout,
        )
        .future
      _ <- requestRegistry.register(ExampleFutures.fast, defaultTimeout).future
      _ <- requestRegistry.register(ExampleFutures.fast, defaultTimeout).future
      _ <- requestRegistry.register(ExampleFutures.fast, defaultTimeout).future
      _ = assert(
        requestRegistry.getTimeout(defaultTimeout.get) > duration
      )
      _ = assert(
        requestRegistry
          .getTimeout(defaultTimeout.get) < duration * 2
      )
      _ = assert(requestRegistry.getTimeout(doneTimeout.get) == duration)
    } yield ()
  }

  test("timeout") {
    val promise = Promise[Unit]()
    val requestRegistry = createRegistry()
    for {
      err <- requestRegistry
        .register(ExampleFutures.infinite(promise), defaultTimeout)
        .future
        .failed
      _ = assert(err.isInstanceOf[TimeoutException])
      _ = assertEquals(
        requestRegistry.getTimeout(defaultTimeout.get),
        duration * 3,
      )
      _ <- requestRegistry.register(ExampleFutures.fast, defaultTimeout).future
      _ <- promise.future
    } yield ()
  }

  test("cancel") {
    val promise1 = Promise[Unit]()
    val promise2 = Promise[Unit]()
    val requestRegistry = createRegistry()
    val f1 = requestRegistry.register(
      ExampleFutures.infinite(promise1),
      timeout = None,
    )
    val f2 = requestRegistry.register(
      ExampleFutures.infinite(promise2),
      timeout = None,
    )
    requestRegistry.cancel()
    for {
      err <- f1.future.failed
      _ = assert(err.isInstanceOf[CancellationException])
      err2 <- f2.future.failed
      _ = assert(err2.isInstanceOf[CancellationException])
      _ <- promise1.future
      _ <- promise2.future
    } yield ()
  }

  test("cancel-during-in-flight-send") {
    // `register` invokes `action()` inline, and that call is the JSON-RPC
    // send, which blocks for as long as a wedged build server doesn't read
    // (see scalameta/metals#3464). Teardown must not wait for it, and the
    // request that finishes sending afterwards must not outlive the drain.
    val sendStarted = new CountDownLatch(1)
    val releaseSend = new CountDownLatch(1)
    val promise = Promise[Unit]()
    val requestRegistry = createRegistry()
    val send = ExampleFutures.infinite(promise)
    val registering = Future {
      requestRegistry.register(
        () => {
          sendStarted.countDown()
          releaseSend.await()
          send()
        },
        timeout = None,
      )
    }
    for {
      _ <- Future(sendStarted.await())
      // fails with a `TimeoutException` if `cancel()` queues behind the send
      _ <- Future(requestRegistry.cancel())
        .withTimeout(duration * 10, reason = Some("cancelling the registry"))
      _ = releaseSend.countDown()
      registered <- registering
      err <- registered.future.failed
      _ = assert(err.isInstanceOf[CancellationException])
      _ <- promise.future
    } yield ()
  }

  test("register-after-cancel-is-rejected") {
    val requestRegistry = createRegistry()
    requestRegistry.cancel()
    assert(requestRegistry.isCancelled)
    for {
      err <- requestRegistry
        .register(ExampleFutures.done, timeout = None)
        .future
        .failed
    } yield assert(err.isInstanceOf[IllegalStateException])
  }

  test("wait-on-timeout") {
    val promise1 = Promise[Unit]()
    var timeoutCount = 0
    val requestRegistry = createRegistry(() => {
      timeoutCount += 1
      if (timeoutCount <= 1) Messages.RequestTimeout.waitAction
      else Messages.RequestTimeout.cancel
    })

    for {
      err <- requestRegistry
        .register(
          ExampleFutures.infinite(promise1),
          askIfCancelTimeout,
        )
        .future
        .failed
      _ = assert(err.isInstanceOf[TimeoutException])
      _ = assert(timeoutCount == 2)
      _ <- promise1.future
    } yield ()
  }

  test("no-user-response") {
    val promise1 = Promise[Unit]()

    val askedUser = Promise[Unit]()
    val userAnswerPromise = Promise[Unit]()
    val requestRegistry =
      new RequestRegistry(
        List(),
        new RequestRegistrySuite.Client(() => {
          askedUser.success(())
          userAnswerPromise.future.map(_ => Messages.RequestTimeout.cancel)
        }),
      )

    val computation = requestRegistry
      .register(
        ExampleFutures.infinite(promise1),
        askIfCancelTimeout,
      )
    for {
      // await for timeout, so the client is asked
      _ <- askedUser.future
      // make computation finish
      _ = promise1.success(())
      _ <- computation.future
      _ = userAnswerPromise.success(())
    } yield ()
  }

}

object RequestRegistrySuite {
  class Client(onTimeout: () => Future[MessageActionItem])
      extends NoopLanguageClient {
    override def showMessageRequest(
        requestParams: ShowMessageRequestParams
    ): CompletableFuture[MessageActionItem] = onTimeout().asJava
  }
}
