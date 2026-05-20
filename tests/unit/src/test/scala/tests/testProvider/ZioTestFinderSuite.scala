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
        "HelloWorldSpec",
        QuickRange(4, 13, 8, 3),
      ),
      (
        "sayHello correctly displays output",
        QuickRange(5, 4, 7, 5),
      ),
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
        "HelloWorldSpec",
        QuickRange(4, 13, 10, 3),
      ),
      (
        "sayHello correctly displays output",
        QuickRange(5, 4, 9, 5),
      ),
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
      (
        "HelloWorldSpec",
        QuickRange(4, 13, 8, 3),
      ),
      (
        "sayHello correctly displays output",
        QuickRange(5, 4, 7, 5),
      ),
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
      (
        "NestedSpec",
        QuickRange(4, 13, 10, 3),
      ),
      (
        "nested suite",
        QuickRange(5, 4, 9, 5),
      ),
      (
        "nested test",
        QuickRange(6, 6, 8, 7),
      ),
    ),
  )

  check(
    "nested-suiteAll",
    """|import zio.test._
       |import zio.test.Assertion._
       |
       |object NestedSuiteAll extends ZIOSpecDefault {
       |  def spec = suite("Outer")(
       |    suiteAll("Inner") {
       |      test("inner test") {
       |        assertTrue(true)
       |      }
       |    }
       |  )
       |}
       |""".stripMargin,
    FullyQualifiedName("NestedSuiteAll"),
    Set(
      (
        "Outer",
        QuickRange(4, 13, 10, 3),
      ),
      (
        "Inner",
        QuickRange(5, 4, 9, 5),
      ),
      (
        "inner test",
        QuickRange(6, 6, 8, 7),
      ),
    ),
  )

  check(
    "nested-suiteAll-2",
    """|import zio.test._
       |import zio.test.Assertion._
       |
       |object NestedSuiteAll extends ZIOSpecDefault {
       |  def spec = suiteAll("Outer")(
       |    suiteAll("Inner") {
       |      test("inner test") {
       |        assertTrue(true)
       |      }
       |    }
       |  )
       |}
       |""".stripMargin,
    FullyQualifiedName("NestedSuiteAll"),
    Set(
      (
        "Outer",
        QuickRange(4, 13, 10, 3),
      ),
      (
        "Inner",
        QuickRange(5, 4, 9, 5),
      ),
      (
        "inner test",
        QuickRange(6, 6, 8, 7),
      ),
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
      (
        "MultipleTestsSpec",
        QuickRange(4, 13, 14, 3),
      ),
      (
        "test1",
        QuickRange(5, 4, 7, 5),
      ),
      (
        "test2",
        QuickRange(8, 4, 10, 5),
      ),
      (
        "test3",
        QuickRange(11, 4, 13, 5),
      ),
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
        "level1",
        QuickRange(5, 4, 13, 5),
      ),
      (
        "DeeplyNestedSpec",
        QuickRange(4, 13, 14, 3),
      ),
      (
        "level2",
        QuickRange(6, 6, 12, 7),
      ),
      (
        "level3",
        QuickRange(7, 8, 11, 9),
      ),
      (
        "deeply nested test",
        QuickRange(8, 10, 10, 11),
      ),
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
      (
        "IgnoredTestSpec",
        QuickRange(4, 13, 11, 3),
      ),
      (
        "active test",
        QuickRange(5, 4, 7, 5),
      ),
      (
        "ignored test",
        QuickRange(8, 4, 10, 5),
      ),
    ),
  )

  check(
    "with-provide-layer",
    """|import zio.test._
       |import zio.test.Assertion._
       |
       |trait SomeLayer {}
       |
       |object HelloTest extends ZIOSpecDefault {
       |  def spec = suite("HelloTest")(
       |    test("testA1") {
       |      assertTrue(true)
       |    },
       |    test("testA2") {
       |      assertTrue(true)
       |    },
       |    test("testA3") {
       |      assertTrue(true)
       |    }
       |  ).provideLayer(ZLayer.succeed(new SomeLayer {}))
       |}
       |""".stripMargin,
    FullyQualifiedName("HelloTest"),
    Set(
      (
        "HelloTest",
        QuickRange(6, 13, 16, 3),
      ),
      (
        "testA1",
        QuickRange(7, 4, 9, 5),
      ),
      (
        "testA2",
        QuickRange(10, 4, 12, 5),
      ),
      (
        "testA3",
        QuickRange(13, 4, 15, 5),
      ),
    ),
  )

  check(
    "with-chained-method-calls",
    """|import zio.test._
       |import zio.test.Assertion._
       |
       |trait SomeLayer {}
       |
       |object HelloTest extends ZIOSpecDefault {
       |  def spec = suite("HelloTest")(
       |    test("testB1") {
       |      assertTrue(true)
       |    },
       |    test("testB2") {
       |      assertTrue(true)
       |    }
       |  ).provideLayer(ZLayer.succeed(new SomeLayer {})).timeout(java.time.Duration.ofSeconds(30))
       |}
       |""".stripMargin,
    FullyQualifiedName("HelloTest"),
    Set(
      (
        "HelloTest",
        QuickRange(6, 13, 13, 3),
      ),
      (
        "testB1",
        QuickRange(7, 4, 9, 5),
      ),
      (
        "testB2",
        QuickRange(10, 4, 12, 5),
      ),
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
