package tests.pc

import java.nio.file.Paths

import scala.meta.internal.metals.CompilerOffsetParams

import munit.Location
import munit.TestOptions
import tests.SignatureHelpUtils

abstract class BaseJavaSignatureHelpSuite
    extends BaseJavaPCSuite
    with SignatureHelpUtils {
  def check(
      name: TestOptions,
      original: String,
      expected: String,
      includeDocs: Boolean = false,
  )(implicit loc: Location): Unit = {
    test(name) {
      val (code, offset) = params(original, "Test.java")
      val result =
        presentationCompiler
          .signatureHelp(
            CompilerOffsetParams(Paths.get("Test.java").toUri(), code, offset)
          )
          .get()
      val out = if (result != null) {
        render(result, includeDocs)
      } else {
        ""
      }
      assertNoDiff(
        out.toString(),
        expected,
      )
    }
  }
}
