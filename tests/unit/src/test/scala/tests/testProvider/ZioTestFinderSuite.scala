package tests.testProvider

import java.nio.file.Paths

import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.testProvider.FullyQualifiedName
import scala.meta.internal.metals.testProvider.frameworks.ZioTestFinder
import scala.meta.io.AbsolutePath

import munit.FunSuite
import munit.Location
import tests.QuickRange
import tests.TreeUtils

class ZioTestFinderSuite extends FunSuite {

  check(
    "basic-spec",
    """|import zio.test._
       |import zio.test.Assertion._
       |
       |object HelloWorldSpec extends ZIOSpecDefault {
       |  def spec = suite("HelloWorldSpec")(
       |    test("sayHello correctly displays output") {
       |      assertTrue(true)
       |    }
       |  )
       |}
       |""".stripMargin,
    FullyQualifiedName("HelloWorldSpec"),
    Set(
      (
        "HelloWorldSpec sayHello correctly displays output",
        QuickRange(5, 4, 7, 5),
      )
    ),
  )
  check(
    "basic-spec-with-gen",
    """|import zio.test._
       |import zio.test.Assertion._
       |
       |object HelloWorldSpec extends ZIOSpecDefault {
       |  def spec = suite("HelloWorldSpec")(
       |    test("sayHello correctly displays output") {
       |      get(Gen.int) { i =>
       |        assertTrue(i > 0)
       |      }
       |    }
       |  )
       |}
       |""".stripMargin,
    FullyQualifiedName("HelloWorldSpec"),
    Set(
      (
        "HelloWorldSpec sayHello correctly displays output",
        QuickRange(5, 4, 9, 5),
      )
    ),
  )
  check(
    "basic-mutable-spec",
    """|import zio.test._
       |import zio.test.Assertion._
       |
       |object HelloWorldSpec extends ZIOSpecDefault {
       |  def spec = suiteAll("HelloWorldSpec") {
       |    test("sayHello correctly displays output") {
       |      assertTrue(true)
       |    }
       |  }
       |}
       |""".stripMargin,
    FullyQualifiedName("HelloWorldSpec"),
    Set(
      ("sayHello correctly displays output", QuickRange(5, 4, 7, 5))
    ),
  )
  check(
    "nested-spec",
    """|import zio.test._
       |import zio.test.Assertion._
       |
       |object NestedSpec extends ZIOSpecDefault {
       |  def spec = suite("NestedSpec")(
       |    suite("nested suite")(
       |      test("nested test") {
       |        assertTrue(true)
       |      }
       |    )
       |  )
       |}
       |""".stripMargin,
    FullyQualifiedName("NestedSpec"),
    Set(
      ("NestedSpec nested suite nested test", QuickRange(6, 6, 8, 7))
    ),
  )

  check(
    "multiple-tests",
    """|import zio.test._
       |import zio.test.Assertion._
       |
       |object MultipleTestsSpec extends ZIOSpecDefault {
       |  def spec = suite("MultipleTestsSpec")(
       |    test("test1") {
       |      assertTrue(true)
       |    },
       |    test("test2") {
       |      assertTrue(true)
       |    },
       |    test("test3") {
       |      assertTrue(true)
       |    }
       |  )
       |}
       |""".stripMargin,
    FullyQualifiedName("MultipleTestsSpec"),
    Set(
      ("test1", QuickRange(5, 4, 7, 5)),
      ("test2", QuickRange(8, 4, 10, 5)),
      ("test3", QuickRange(11, 4, 13, 5)),
    ),
  )

  check(
    "deeply-nested",
    """|import zio.test._
       |import zio.test.Assertion._
       |
       |object DeeplyNestedSpec extends ZIOSpecDefault {
       |  def spec = suite("DeeplyNestedSpec")(
       |    suite("level1")(
       |      suite("level2")(
       |        suite("level3")(
       |          test("deeply nested test") {
       |            assertTrue(true)
       |          }
       |        )
       |      )
       |    )
       |  )
       |}
       |""".stripMargin,
    FullyQualifiedName("DeeplyNestedSpec"),
    Set(
      (
        "DeeplyNestedSpec level1 level2 level3 deeply nested test",
        QuickRange(8, 10, 10, 11),
      )
    ),
  )

  check(
    "ignored-test",
    """|import zio.test._
       |import zio.test.Assertion._
       |
       |object IgnoredTestSpec extends ZIOSpecDefault {
       |  def spec = suite("IgnoredTestSpec")(
       |    test("active test") {
       |      assertTrue(true)
       |    },
       |    test("ignored test") {
       |      assertTrue(true)
       |    } @@ ignore
       |  )
       |}
       |""".stripMargin,
    FullyQualifiedName("IgnoredTestSpec"),
    Set(
      ("active test", QuickRange(5, 4, 7, 5)),
      ("ignored test", QuickRange(8, 4, 10, 5)),
    ),
  )

  def check(
      name: String,
      code: String,
      suiteName: FullyQualifiedName,
      expectedTests: Set[(String, QuickRange)],
  )(implicit loc: Location): Unit = {
    test(name) {
      val (buffers, trees) = TreeUtils.getTrees(BuildInfo.scala213)
      val path = AbsolutePath(Paths.get("src/main/scala/Test.scala"))
      buffers.put(path, code)

      val finder = new ZioTestFinder(
        trees = trees
      )

      val tests = finder.findTests(path, suiteName)
      val actualTests = tests.map { t =>
        val range = t.location.getRange()
        (
          t.name,
          QuickRange(
            range.getStart().getLine(),
            range.getStart().getCharacter(),
            range.getEnd().getLine(),
            range.getEnd().getCharacter(),
          ),
        )
      }.toSet

      assertEquals(
        actualTests,
        expectedTests,
        s"Test discovery failed for $name",
      )
    }
  }
}
