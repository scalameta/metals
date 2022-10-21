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
  )(implicit location: Location): Unit =
    test(name) {
      val original = s"""|object Main {
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
            URI.create("file:/Rename.scala"),
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
}
