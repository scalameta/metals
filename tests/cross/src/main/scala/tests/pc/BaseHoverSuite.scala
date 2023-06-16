package tests.pc

import java.nio.file.Paths

import scala.meta.XtensionSyntax
import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.metals.CompilerRangeParams
import scala.meta.internal.mtags.MtagsEnrichments._

import munit.Location
import munit.TestOptions
import tests.BasePCSuite
import tests.RangeReplace
import tests.TestHovers

abstract class BaseHoverSuite
    extends BasePCSuite
    with TestHovers
    with RangeReplace {

  def check(
      testOpt: TestOptions,
      original: String,
      expected: String,
      includeRange: Boolean = false,
      automaticPackage: Boolean = true,
      compat: Map[String, String] = Map.empty,
  )(implicit loc: Location): Unit = {
    test(testOpt) {
      val filename = "Hover.scala"
      val pkg = scala.meta.Term.Name(testOpt.name).syntax
      val noRange = original
        .replace("<<", "")
        .replace(">>", "")
      val packagePrefix =
        if (automaticPackage) s"package $pkg\n"
        else ""
      val codeOriginal = packagePrefix + noRange
      val (code, so, eo) = hoverParams(codeOriginal, filename)
      val pcParams = if (so == eo) {
        CompilerOffsetParams(Paths.get(filename).toUri(), code, so)
      } else {
        CompilerRangeParams(Paths.get(filename).toUri(), code, so, eo)
      }
      val hover = presentationCompiler
        .hover(pcParams)
        .get()
        .asScala
        .map(_.toLsp())
      val obtained: String = renderAsString(code, hover, includeRange)
      assertNoDiff(
        obtained,
        getExpected(expected, compat, scalaVersion),
      )
      for {
        h <- hover
        range <- Option(h.getRange)
      } {
        val base =
          codeOriginal.replace("@@", "").replace("%<%", "").replace("%>%", "")
        val withRange = replaceInRange(base, range)
        assertNoDiff(
          withRange,
          packagePrefix + original
            .replace("@@", "")
            .replace("%<%", "")
            .replace("%>%", ""),
          "Invalid range",
        )
      }
    }
  }

  override val compatProcess: Map[String, String => String] = Map(
    "2.13" -> { s =>
      s.replace(
        "def map[B, That](f: Int => B)(implicit bf: CanBuildFrom[List[Int],B,That]): That",
        "def map[B](f: Int => B): List[B]",
      )
    }
  )

}
