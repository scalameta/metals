package tests.pc

import java.util.concurrent.CompletableFuture
import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.async.CompletableCancelToken
import tests.DelegatingGlobalSymbolIndex
import tests.BasePCSuite
import scala.meta.internal.mtags.SymbolDefinition
import scala.meta.internal.mtags.Symbol
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import scala.meta.pc.PresentationCompiler
import scala.meta.pc.OffsetParams
import java.util.concurrent.atomic.AtomicReference
import munit.Location
import java.net.URI
import tests.BuildInfoVersions

class InterruptPresentationCompilerSuite extends BasePCSuite {
  class InterruptSymbolIndex extends DelegatingGlobalSymbolIndex() {
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

  // @tgodzik currently not handled for Dotty
  override def excludedScalaVersions: Set[String] =
    BuildInfoVersions.scala3Versions.toSet

  override def beforeEach(context: BeforeEach): Unit = {
    index.asInstanceOf[InterruptSymbolIndex].reset()
    super.beforeEach(context)
  }

  override def requiresScalaLibrarySources: Boolean = true

  override val index: DelegatingGlobalSymbolIndex =
    new InterruptSymbolIndex()

  def check(
      name: String,
      original: String,
      act: (PresentationCompiler, OffsetParams) => CompletableFuture[_]
  )(implicit loc: Location): Unit = {
    test(name) {
      val (code, offset) = this.params(original)
      val interrupt = index.asInstanceOf[InterruptSymbolIndex]
      try {
        val result = act(
          presentationCompiler,
          CompilerOffsetParams(
            URI.create("file:///A.scala"),
            code,
            offset,
            interrupt.token.get()
          )
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
