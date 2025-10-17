package tests.pc

import java.net.URI
import java.nio.file.Paths

import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.metals.CompilerRangeParams
import scala.meta.internal.mtags.MtagsEnrichments._

import munit.Location
import munit.TestOptions
import tests.TestHovers

class BaseJavaHoverSuite extends BaseJavaPCSuite with TestHovers {

  def check(
      testOpt: TestOptions,
      original: String,
      expected: String,
      includeRange: Boolean = false,
      automaticPackage: Boolean = true,
      uri: URI = Paths.get("Hover.java").toUri(),
  )(implicit loc: Location): Unit = {
    test(testOpt) {
      val filename = "Hover.java"
      val pkg = packageName(testOpt.name)
      val noRange = original
        .replace("<<", "")
        .replace(">>", "")
      val packagePrefix =
        if (automaticPackage) s"package $pkg;\n"
        else ""
      val codeOriginal = packagePrefix + noRange
      val (code, so, eo) = hoverParams(codeOriginal, filename)

      val pcParams = if (so == eo) {
        CompilerOffsetParams(uri, code, so)
      } else {
        CompilerRangeParams(uri, code, so, eo)
      }
      val hover = presentationCompiler.hover(pcParams).get()

      val obtained: String =
        renderAsString(
          code,
          hover.asScala.map(_.toLsp()),
          includeRange,
        )

      assertNoDiff(
        obtained,
        expected,
      )
    }
  }

}
