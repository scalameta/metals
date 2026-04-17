package tests.pc

import java.net.URI
import java.nio.file.Paths

import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.metals.CompilerRangeParams
import scala.meta.internal.mtags.MtagsEnrichments._

import munit.Location
import munit.TestOptions
import tests.TestHovers

class BaseProtoHoverSuite extends BaseProtoPCSuite with TestHovers {

  def check(
      testOpt: TestOptions,
      original: String,
      expected: String,
      includeRange: Boolean = false,
      uri: URI = Paths.get("Hover.proto").toUri(),
  )(implicit loc: Location): Unit = {
    test(testOpt) {
      val filename = "Hover.proto"
      val noRange = original
        .replace("<<", "")
        .replace(">>", "")
      val (code, so, eo) = hoverParams(noRange, filename)

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
