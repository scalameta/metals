package tests.search

import scala.meta.metals.ScalametaEnrichments._
import scala.meta.metals.search.TokenEditDistance
import org.langmeta.languageserver.InputEnrichments._
import tests.MegaSuite

object TokenEditDistanceTest extends MegaSuite {
  def check(
      name: String,
      revised: String,
      original: String,
      expected: String,
      keyword: String = "List"
  ): Unit = {
    test(name) {
      val edit = TokenEditDistance(original, revised).get
      val offset = revised.indexOf(keyword)
      val obtained = edit
        .toOriginal(offset)
        .map { pos =>
          val Right(reverse) = edit.toRevised(pos.start)
          assert(reverse.contains(offset))
          s"""|${pos.lineContent}
              |${pos.caret}""".stripMargin
        }
        .getOrElse("<none>")
      assertNoDiff(obtained, expected)
    }
  }

  check(
    "insert",
    revised = """
                |object a {
                |  "msg".substrin
                |  List(1)
                |}""".stripMargin,
    original = """
                 |object a {
                 |  List(1) // <--
                 |}
    """.stripMargin,
    expected = """
                 |  List(1) // <--
                 |  ^
                 |""".stripMargin,
  )

  check(
    "change",
    revised = """
                |object a {
                |  "msg".substrin
                |  List(1)
                |}""".stripMargin,
    original = """
                 |object a {
                 |  this.changed()
                 |  List(1) // <--
                 |}
    """.stripMargin,
    expected = """
                 |  List(1) // <--
                 |  ^
                 |""".stripMargin,
  )

  check(
    "delete",
    revised = """
                |object a {
                |  List(1)
                |}""".stripMargin,
    original = """
                 |object a {
                 |  remove(this)
                 |  List(1) // <--
                 |}
    """.stripMargin,
    expected = """
                 |  List(1) // <--
                 |  ^
                 |""".stripMargin,
  )

  check(
    "none",
    revised = """
                |object a {
                |  List(1)
                |}""".stripMargin,
    original = """
                 |object a {
                 |  Something()
                 |}
    """.stripMargin,
    expected = "<none>",
  )

  check(
    "moved",
    revised = """
                |object a {
                |  def foo = {
                |    println(1)
                |  }
                |  List(1)
                |}""".stripMargin,
    original = """
                 |object a {
                 |  List(1)
                 |  def foo = {
                 |    Fuz(1)
                 |  }
                 |}
               """.stripMargin,
    expected = "<none>",
  )

}
