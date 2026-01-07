package tests.pc

import java.nio.file.Paths
import scala.meta.internal.metals.CompilerOffsetParams
import tests.BaseSignatureHelpSuite

class SignatureHelpRegressionSuite extends BaseSignatureHelpSuite {

  test("validate-active-parameter-is-non-negative") {
    val code =
      """
        |object Foo { 
        |  def bar(params: Int): Unit = ??? 
        |  bar(params = Int@@) 
        |}
      """.stripMargin

    val (source, offset) = params(code, "A.scala")
    val result = presentationCompiler
      .signatureHelp(
        CompilerOffsetParams(Paths.get("A.scala").toUri(), source, offset)
      )
      .get()

    if (result != null) {
      assert(
        result.getActiveParameter >= 0,
        s"activeParameter should be >= 0, but was ${result.getActiveParameter}"
      )
    }
  }

  test("validate-active-parameter-is-non-negative-2") {
    val code =
      """
        |object Foo { 
        |  def bar(params: Int): Unit = ??? 
        |  bar(params = @@) 
        |}
      """.stripMargin

    val (source, offset) = params(code, "A.scala")
    val result = presentationCompiler
      .signatureHelp(
        CompilerOffsetParams(Paths.get("A.scala").toUri(), source, offset)
      )
      .get()

    if (result != null) {
      assert(
        result.getActiveParameter >= 0,
        s"activeParameter should be >= 0, but was ${result.getActiveParameter}"
      )
    }
  }
}
