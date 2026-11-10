package tests.pc

import java.net.URI

import scala.jdk.CollectionConverters._

import scala.meta.internal.metals.CompilerInlayHintsParams
import scala.meta.internal.metals.CompilerRangeParams

import munit.Location
import munit.TestOptions
import tests.TestInlayHints

abstract class BaseJavaInlayHintsSuite extends BaseJavaPCSuite {

  def check(
      testOpt: TestOptions,
      expected: String,
      inferredTypes: Boolean = true,
      namedParameters: Boolean = true,
  )(implicit loc: Location): Unit = test(testOpt) {
    val pkg = packageName(testOpt.name)
    val code =
      s"package $pkg;\n${TestInlayHints.removeInlayHints(expected)}"
    val rangeParams = CompilerRangeParams(
      URI.create("file:///InlayHints.java"),
      code,
      0,
      code.length(),
    )
    val pcParams = CompilerInlayHintsParams(
      rangeParams,
      inferredTypes = inferredTypes,
      typeParameters = false,
      implicitParameters = false,
      hintsXRayMode = false,
      byNameParameters = false,
      implicitConversions = false,
      namedParameters = namedParameters,
      hintsInPatternMatch = false,
      closingLabels = false,
    )
    val hints =
      presentationCompiler.inlayHints(pcParams).get().asScala.toList
    val obtained =
      TestInlayHints.applyInlayHints(code, hints, withTooltip = false)
    assertNoDiff(obtained, s"package $pkg;\n$expected")
  }
}
