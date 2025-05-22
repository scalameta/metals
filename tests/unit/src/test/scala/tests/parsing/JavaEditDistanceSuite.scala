package tests.parsing

import scala.meta.inputs.Input
import scala.meta.internal.parsing.TokenEditDistance

import tests.BaseSuite

class JavaEditDistanceSuite extends BaseSuite {

  test("changed") {

    val original = Input.VirtualFile(
      "Main.java",
      """|// different comment
         |public class A{
         |  abstract void hello()
         |}
         |""".stripMargin,
    )

    val revised = Input.VirtualFile(
      "Main.java",
      """|// comment
         |
         |public class A{
         |  abstract void hello()
         |}
         |""".stripMargin,
    )

    val distance =
      TokenEditDistance(original, revised, scalaVersionSelector = null)

    val diffString =
      if (isWindows) "Diff(22 tokens)"
      else "Diff(19 tokens)"

    assertNoDiff(
      distance.map(_.toString()).getOrElse("TokenizationError"),
      diffString,
    )
  }

  test("no-change") {

    val original = Input.VirtualFile(
      "Main.java",
      """|
         |public class A{
         |
         |}
         |""".stripMargin,
    )

    val revised = Input.VirtualFile(
      "Main.java",
      """|
         |public class A{
         |
         |}
         |""".stripMargin,
    )

    val distance =
      TokenEditDistance(original, revised, scalaVersionSelector = null)

    assertNoDiff(
      distance.map(_.toString()).getOrElse("TokenizationError"),
      "unchanged",
    )

  }
}
