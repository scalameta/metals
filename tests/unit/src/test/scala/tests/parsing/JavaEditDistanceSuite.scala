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

  test("comparison-cutoff") {
    val original = Input.VirtualFile(
      "Main.java",
      (0 until 40)
        .map(i => s"  int a$i = $i;")
        .mkString("class A {\n", "\n", "\n}\n"),
    )
    val revised = Input.VirtualFile(
      "Main.java",
      (0 until 40)
        .map(i => s"  int b$i = ${i + 1};")
        .mkString("class B {\n", "\n", "\n}\n"),
    )

    val cutoff = TokenEditDistance(
      original,
      revised,
      scalaVersionSelector = null,
      maxComparisons = 100,
    )
    assertEquals(cutoff, Right(TokenEditDistance.NoMatch))

    val distance = TokenEditDistance(
      original,
      revised,
      scalaVersionSelector = null,
      maxComparisons = 100000,
    )
    assert(
      distance.exists(_.toString().startsWith("Diff(")),
      clue(distance),
    )
  }

  test("one-sided-path-cutoff") {
    val original = Input.VirtualFile("Main.java", "class A {}\n")
    val revised = Input.VirtualFile(
      "Main.java",
      (0 until 1000)
        .map(i => s"  int a$i;")
        .mkString("class A {}\n", "\n", "\n"),
    )

    val cutoff = TokenEditDistance(
      original,
      revised,
      scalaVersionSelector = null,
      maxComparisons = 1000000,
    )
    assertEquals(cutoff, Right(TokenEditDistance.NoMatch))

    val distance = TokenEditDistance(
      original,
      revised,
      scalaVersionSelector = null,
      maxComparisons = 100000000,
    )
    assert(
      distance.exists(_.toString().startsWith("Diff(")),
      clue(distance),
    )
  }
}
