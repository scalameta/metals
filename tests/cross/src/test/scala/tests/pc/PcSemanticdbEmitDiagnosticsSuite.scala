package tests.pc

import java.net.URI

import scala.meta.internal.pc.PresentationCompilerConfigImpl
import scala.meta.pc.PresentationCompilerConfig

class PcSemanticdbEmitDiagnosticsSuite extends PcSemanticdbSuite {

  override protected def config: PresentationCompilerConfig =
    PresentationCompilerConfigImpl(emitDiagnostics = true)

  for (
    filename <- List(
      "CloseS.scala",
      "CloseC.sc",
      "CloseW.worksheet.sc",
      "CloseM.mill.scala"
    )
  )
    test(s"document-after-close-$filename") {
      val uri = new URI(s"file:///close/$filename")
      val code = s"""object ${filename.take(6)} { val a: Int = "" }"""
      assertNoDiff(diagnostics(uri, code), StringNotInt)
      presentationCompiler.didClose(uri)
      assertNoDiff(diagnostics(uri, code), StringNotInt)
    }

}
