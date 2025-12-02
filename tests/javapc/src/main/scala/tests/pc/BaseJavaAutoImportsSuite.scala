package tests.pc

import java.nio.file.Paths

import scala.jdk.CollectionConverters._

import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.internal.metals.TextEdits
import scala.meta.pc.AutoImportsResult
import scala.meta.pc.CancelToken

import munit.Location
import munit.TestOptions

trait BaseJavaAutoImportsSuite extends BaseJavaPCSuite {

  protected def cancelToken: CancelToken = EmptyCancelToken

  def check(
      name: String,
      original: String,
      expected: String,
      filename: String = "A.java",
  )(implicit loc: Location): Unit =
    test(name) {
      val imports = getAutoImports(original, filename)
      val obtained = imports.map(_.packageName()).mkString("\n")
      assertNoDiff(obtained, expected)
    }

  def checkEdit(
      name: TestOptions,
      original: String,
      expected: String,
      selection: Int = 0,
      filename: String = "A.java",
  )(implicit
      loc: Location
  ): Unit =
    checkEditSelection(name, filename, original, expected, selection)

  def checkEditSelection(
      name: TestOptions,
      filename: String,
      original: String,
      expected: String,
      selection: Int,
  )(implicit
      loc: Location
  ): Unit =
    test(name) {
      val imports = getAutoImports(original, filename)
      if (imports.size <= selection) fail("obtained no expected imports")
      val edits = imports(selection).edits().asScala.toList
      val (code, _, _) = autoImportsParams(original, filename)
      val obtained = TextEdits.applyEdits(code, edits)
      assertNoDiff(obtained, expected)
    }

  protected def getAutoImports(
      original: String,
      filename: String,
  ): List[AutoImportsResult] = {
    val (code, symbol, offset) = autoImportsParams(original, filename)
    val result = presentationCompiler
      .autoImports(
        symbol,
        CompilerOffsetParams(
          Paths.get(filename).toUri,
          code,
          offset,
          cancelToken,
        ),
        java.lang.Boolean.FALSE,
      )
      .get()
    result.asScala.toList
  }
}
