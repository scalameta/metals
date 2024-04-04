package tests

import java.net.URI

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.CompilerInlayHintsParams
import scala.meta.internal.metals.CompilerRangeParams

import munit.Location
import munit.TestOptions

class BaseInlayHintsSuite extends BasePCSuite {

  def check(
      name: TestOptions,
      base: String,
      expected: String,
      compat: Map[String, String] = Map.empty,
      hintsInPatternMatch: Boolean = false
  )(implicit location: Location): Unit =
    test(name) {
      def pkgWrap(text: String) =
        if (text.contains("package")) text
        else s"package ${scala.meta.Term.Name(name.name)}\n$text"

      val withPkg = pkgWrap(base)
      val rangeParams = CompilerRangeParams(
        URI.create("file:/InlayHints.scala"),
        withPkg,
        0,
        withPkg.length()
      )
      val pcParams = CompilerInlayHintsParams(
        rangeParams,
        true,
        true,
        true,
        true,
        hintsInPatternMatch
      )

      val inlayHints = presentationCompiler
        .inlayHints(
          pcParams
        )
        .get()
        .asScala
        .toList

      val obtained = TestInlayHints.applyInlayHints(withPkg, inlayHints)

      assertEquals(
        obtained,
        pkgWrap(getExpected(expected, compat, scalaVersion))
      )

    }
}
