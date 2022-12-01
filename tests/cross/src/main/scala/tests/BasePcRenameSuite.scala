package tests

import java.net.URI

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.internal.metals.TextEdits

import munit.Location
import munit.TestOptions

class BasePcRenameSuite extends BasePCSuite with RangeReplace {

  def check(
      name: TestOptions,
      methodBody: String,
      newName: String = "newName",
      filename: String = "A.scala",
      wrap: Boolean = true,
  )(implicit location: Location): Unit =
    test(name) {
      val original =
        if (!wrap) methodBody
        else
          s"""|object Main {
              |def method() = {
              |$methodBody
              |}
              |}
              |""".stripMargin

      val edit = original.replaceAll("(<<|>>)", "")
      val expected =
        original.replaceAll("@@", "").replaceAll("\\<\\<\\S*\\>\\>", newName)
      val base = original.replaceAll("(<<|>>|@@)", "")
      val (code, offset) = params(edit)
      val renames = presentationCompiler
        .rename(
          CompilerOffsetParams(
            URI.create(s"file:/$filename"),
            code,
            offset,
            EmptyCancelToken,
          ),
          newName,
        )
        .get()
        .asScala
        .toList

      assertEquals(
        TextEdits.applyEdits(base, renames),
        expected,
      )

    }

  def prepare(
      name: TestOptions,
      input: String,
      filename: String = "A.scala",
  ): Unit = {
    test(name) {
      val edit = input.replaceAll("(<<|>>)", "")
      val expected =
        input.replaceAll("@@", "")
      val base = input.replaceAll("(<<|>>|@@)", "")
      val (code, offset) = params(edit)
      val range = presentationCompiler
        .prepareRename(
          CompilerOffsetParams(
            URI.create(s"file:/$filename"),
            code,
            offset,
            EmptyCancelToken,
          )
        )
        .get()

      val withRange =
        if (range.isEmpty()) base else replaceInRange(base, range.get())
      assertNoDiff(
        withRange,
        expected,
      )
    }
  }
}
