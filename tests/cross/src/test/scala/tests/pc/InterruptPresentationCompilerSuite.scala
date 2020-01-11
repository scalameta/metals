package tests.pc

import java.util.concurrent.CompletableFuture
import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.async.CompletableCancelToken
import tests.DelegatingGlobalSymbolIndex
import tests.BasePCSuite
import scala.meta.internal.mtags.SymbolDefinition
import scala.meta.internal.mtags.Symbol
import java.util.concurrent.CancellationException
import scala.meta.internal.mtags.OnDemandSymbolIndex
import java.util.concurrent.atomic.AtomicBoolean
import scala.meta.pc.PresentationCompiler
import scala.meta.pc.OffsetParams
import java.util.concurrent.atomic.AtomicReference
import munit.Location

class InterruptPresentationCompilerSuite extends BasePCSuite {
  class InterruptSymbolIndex
      extends DelegatingGlobalSymbolIndex(OnDemandSymbolIndex()) {
    val token = new AtomicReference(new CompletableCancelToken())
    val isInterrupted = new AtomicBoolean(false)
    def reset(): Unit = {
      token.set(new CompletableCancelToken())
      isInterrupted.set(false)
    }
    override def definition(symbol: Symbol): Option[SymbolDefinition] = {
      token.get().cancel()
      isInterrupted.set(Thread.interrupted())
      super.definition(symbol)
    }
  }

  val interrupt = new InterruptSymbolIndex()

  override def beforeEach(context: BeforeEach): Unit = {
    interrupt.reset()
    super.beforeEach(context)
  }

  override def beforeAll(): Unit = {
    index.underlying = interrupt
    indexScalaLibrary()
  }

  def check(
      name: String,
      original: String,
      act: (PresentationCompiler, OffsetParams) => CompletableFuture[_]
  )(implicit loc: Location): Unit = {
    test(name) {
      val (code, offset) = this.params(original)
      try {
        val result = act(
          pc,
          CompilerOffsetParams("A.scala", code, offset, interrupt.token.get())
        ).get()
        fail(s"Expected cancellation exception. Obtained $result")
      } catch {
        case _: CancellationException => () // OK
      }
      val isInterrupted = interrupt.isInterrupted.get()
      Predef.assert(
        !isInterrupted,
        "thread was interrupted, expected no interruption."
      )
    }
  }

  check(
    "hover",
    """
      |object A {
      |  val x = "".stripS@@uffix("")
      |}
      |""".stripMargin,
    (pc, params) => {
      pc.hover(params)
    }
  )

  check(
    "signature-help",
    """
      |object A {
      |  assert(@@)
      |}
      |""".stripMargin,
    (pc, params) => {
      pc.signatureHelp(params)
    }
  )

}
