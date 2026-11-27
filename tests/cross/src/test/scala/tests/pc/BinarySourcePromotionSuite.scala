package tests.pc

import java.nio.file.Path
import java.nio.file.Paths

import scala.meta.internal.pc.PresentationCompilerConfigImpl
import scala.meta.internal.semanticdb.TextDocument

import coursierapi.Fetch
import tests.BasePCSuite

class BinarySourcePromotionSuite extends BasePCSuite {

  override protected def config: PresentationCompilerConfigImpl =
    PresentationCompilerConfigImpl(emitDiagnostics = true)

  override protected def extraLibraries(fetch: Fetch): Seq[Path] = {
    val classes = classOf[promotion.Envelope].getProtectionDomain.getCodeSource
    Seq(Paths.get(classes.getLocation.toURI))
  }

  test("nested-result-from-classfile-then-source") {
    def check(filename: String, code: String): Unit = {
      val uri = tmp.resolve(filename).toURI
      val bytes = presentationCompiler.semanticdbTextDocument(uri, code).get()
      val doc = TextDocument.parseFrom(bytes)
      assert(doc.occurrences.nonEmpty)
      assertEquals(doc.diagnostics.map(_.message), Nil)
    }

    // Typing Payload first loads Envelope.Result from the test classpath.
    check(
      "Payload.scala",
      """|package tests.pc.promotion
         |class Payload { def discard(): Envelope.Result = new Envelope.Result }
         |""".stripMargin
    )
    val envelope =
      """|package tests.pc.promotion
         |trait Envelope {
         |  def payload: Payload
         |  def discard(): Envelope.Base = payload.discard()
         |}
         |object Envelope { class Base; final class Result extends Base }
         |""".stripMargin
    check("Envelope.scala", envelope)
    check("Envelope.scala", envelope)
  }
}

package promotion {
  trait Envelope
  object Envelope {
    class Base
    final class Result extends Base
  }
}
