package tests

import java.nio.file.Paths

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.metals.TextEdits
import scala.meta.pc.AutoImportsResult

import munit.Location
import munit.TestOptions

trait BaseAutoImportsSuite extends BaseCodeActionSuite {

  val isExtensionMethods: Boolean = false

  def check(
      name: String,
      original: String,
      expected: String,
      compat: Map[String, String] = Map.empty,
  )(implicit loc: Location): Unit =
    test(name) {
      val imports = getAutoImports(original, "A.scala")
      val obtained = imports.map(_.packageName()).mkString("\n")
      assertNoDiff(
        obtained,
        getExpected(expected, compat, scalaVersion),
      )
    }

  def checkEdit(
      name: TestOptions,
      original: String,
      expected: String,
      selection: Int = 0,
      filename: String = "A.scala",
      compat: Map[String, String] = Map.empty,
  )(implicit
      loc: Location
  ): Unit =
    checkEditSelection(name, filename, original, expected, selection, compat)

  def checkAmmoniteEdit(
      name: TestOptions,
      original: String,
      expected: String,
      selection: Int = 0,
      compat: Map[String, String] = Map.empty,
  )(implicit
      loc: Location
  ): Unit =
    checkEditSelection(
      name,
      "script.amm.sc.scala",
      original,
      expected,
      selection,
      compat,
    )

  def checkEditSelection(
      name: TestOptions,
      filename: String,
      original: String,
      expected: String,
      selection: Int,
      compat: Map[String, String] = Map.empty,
  )(implicit
      loc: Location
  ): Unit =
    test(name) {
      val imports = getAutoImports(original, filename)
      if (imports.size <= selection) fail("obtained no expected imports")
      val edits = imports(selection).edits().asScala.toList
      val (code, _, _) = params(original)
      val obtained = TextEdits.applyEdits(code, edits)
      assertNoDiff(obtained, getExpected(expected, compat, scalaVersion))
    }

  def getAutoImports(
      original: String,
      filename: String,
  ): List[AutoImportsResult] = {
    val (code, symbol, offset) = params(original)
    val result = presentationCompiler
      .autoImports(
        symbol,
        CompilerOffsetParams(
          Paths.get(filename).toUri(),
          code,
          offset,
          cancelToken,
        ),
        isExtensionMethods,
      )
      .get()
    result.asScala.toList
  }

}
