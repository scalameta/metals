package tests

import java.nio.file.Paths

import scala.meta.XtensionSyntax
import scala.meta.internal.metals.CompilerOffsetParams

import munit.Location
import munit.TestOptions

abstract class BaseSignatureHelpSuite
    extends BasePCSuite
    with SignatureHelpUtils {
  def checkDoc(
      name: TestOptions,
      code: String,
      expected: String,
      compat: Map[String, String] = Map.empty
  )(implicit loc: Location): Unit = {
    check(name, code, expected, includeDocs = true, compat = compat)
  }
  def check(
      name: TestOptions,
      original: String,
      expected: String,
      includeDocs: Boolean = false,
      compat: Map[String, String] = Map.empty,
      stableOrder: Boolean = true
  )(implicit loc: Location): Unit = {
    test(name) {
      val pkg = scala.meta.Term.Name(name.name).syntax
      val (code, offset) = params(s"package $pkg\n" + original, "A.scala")
      val result =
        presentationCompiler
          .signatureHelp(
            CompilerOffsetParams(Paths.get("A.scala").toUri(), code, offset)
          )
          .get()
      val out = if (result != null) {
        render(result, includeDocs)
      } else {
        ""
      }
      assertNoDiff(
        sortLines(stableOrder, out),
        sortLines(
          stableOrder,
          getExpected(expected, compat, scalaVersion)
        )
      )
    }
  }

  override val compatProcess: Map[String, String => String] = Map(
    "2.13" -> { s =>
      s.replace("valueOf(obj: Any)", "valueOf(obj: Object)")
        .replace("Map[A, B]: Map", "Map[K, V]: Map")
    }
  )
}
