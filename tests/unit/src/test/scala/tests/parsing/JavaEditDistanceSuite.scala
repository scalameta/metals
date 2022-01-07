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
         |""".stripMargin
    )

    val revised = Input.VirtualFile(
      "Main.java",
      """|// comment
         |
         |public class A{
         |  abstract void hello()
         |}
         |""".stripMargin
    )

    val distance = TokenEditDistance(original, revised, trees = null)

    if (isWindows)
      assertNoDiff(distance.toString(), "TokenEditDistance(22 tokens)")
    else
      assertNoDiff(distance.toString(), "TokenEditDistance(19 tokens)")

  }

  test("no-change") {

    val original = Input.VirtualFile(
      "Main.java",
      """|
         |public class A{
         |
         |}
         |""".stripMargin
    )

    val revised = Input.VirtualFile(
      "Main.java",
      """|
         |public class A{
         |
         |}
         |""".stripMargin
    )

    val distance = TokenEditDistance(original, revised, trees = null)

    assertNoDiff(distance.toString(), "TokenEditDistance(unchanged)")

  }
}
