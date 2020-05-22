package tests.pc

import java.nio.file.Paths

import scala.meta.XtensionSyntax
import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.mtags.MtagsEnrichments._

import munit.Location
import tests.BasePCSuite
import tests.RangeReplace
import tests.TestHovers

abstract class BaseHoverSuite
    extends BasePCSuite
    with TestHovers
    with RangeReplace {

  def check(
      name: String,
      original: String,
      expected: String,
      includeRange: Boolean = false,
      automaticPackage: Boolean = true,
      compat: Map[String, String] = Map.empty
  )(implicit loc: Location): Unit = {
    test(name) {
      val filename = "Hover.scala"
      val pkg = scala.meta.Term.Name(name).syntax
      val noRange = original
        .replace("<<", "")
        .replace(">>", "")
      val packagePrefix =
        if (automaticPackage) s"package $pkg\n"
        else ""
      val codeOriginal = packagePrefix + noRange
      val (code, offset) = params(codeOriginal, filename)
      val hover = presentationCompiler
        .hover(
          CompilerOffsetParams(Paths.get(filename).toUri(), code, offset)
        )
        .get()
      val obtained: String = renderAsString(code, hover.asScala, includeRange)
      assertNoDiff(
        obtained,
        getExpected(expected, compat, scalaVersion)
      )
      for {
        h <- hover.asScala
        range <- Option(h.getRange)
      } {
        val base = codeOriginal.replace("@@", "")
        val withRange = replaceInRange(base, range)
        assertNoDiff(
          withRange,
          packagePrefix + original.replace("@@", ""),
          "Invalid range"
        )
      }
    }
  }

  override val compatProcess: Map[String, String => String] = Map(
    "2.13" -> { s =>
      s.replace(
        "def map[B, That](f: Int => B)(implicit bf: CanBuildFrom[List[Int],B,That]): That",
        "def map[B](f: Int => B): List[B]"
      )
    }
  )

}
