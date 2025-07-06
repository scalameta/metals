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
    "aaaabasic-spec",
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
      Tuple2(
        _1 = "HelloWorldSpec",
        _2 = QuickRange(
          range = Tuple4(
            _1 = 4,
            _2 = 13,
            _3 = 8,
            _4 = 3,
          )
        ),
      ),
      Tuple2(
        _1 = "sayHello correctly displays output",
        _2 = QuickRange(
          range = Tuple4(
            _1 = 5,
            _2 = 4,
            _3 = 7,
            _4 = 5,
          )
        ),
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
      Tuple2(
        _1 = "HelloWorldSpec",
        _2 = QuickRange(
          range = Tuple4(
            _1 = 4,
            _2 = 13,
            _3 = 10,
            _4 = 3,
          )
        ),
      ),
      Tuple2(
        _1 = "sayHello correctly displays output",
        _2 = QuickRange(
          range = Tuple4(
            _1 = 5,
            _2 = 4,
            _3 = 9,
            _4 = 5,
          )
        ),
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
      Tuple2(
        _1 = "HelloWorldSpec",
        _2 = QuickRange(
          range = Tuple4(
            _1 = 4,
            _2 = 13,
            _3 = 8,
            _4 = 3,
          )
        ),
      ),
      Tuple2(
        _1 = "sayHello correctly displays output",
        _2 = QuickRange(
          range = Tuple4(
            _1 = 5,
            _2 = 4,
            _3 = 7,
            _4 = 5,
          )
        ),
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
      Tuple2(
        _1 = "NestedSpec",
        _2 = QuickRange(
          range = Tuple4(
            _1 = 4,
            _2 = 13,
            _3 = 10,
            _4 = 3,
          )
        ),
      ),
      Tuple2(
        _1 = "nested suite",
        _2 = QuickRange(
          range = Tuple4(
            _1 = 5,
            _2 = 4,
            _3 = 9,
            _4 = 5,
          )
        ),
      ),
      Tuple2(
        _1 = "nested test",
        _2 = QuickRange(
          range = Tuple4(
            _1 = 6,
            _2 = 6,
            _3 = 8,
            _4 = 7,
          )
        ),
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
      Tuple2(
        _1 = "Outer",
        _2 = QuickRange(
          range = Tuple4(
            _1 = 4,
            _2 = 13,
            _3 = 10,
            _4 = 3,
          )
        ),
      ),
      Tuple2(
        _1 = "Inner",
        _2 = QuickRange(
          range = Tuple4(
            _1 = 5,
            _2 = 4,
            _3 = 9,
            _4 = 5,
          )
        ),
      ),
      Tuple2(
        _1 = "inner test",
        _2 = QuickRange(
          range = Tuple4(
            _1 = 6,
            _2 = 6,
            _3 = 8,
            _4 = 7,
          )
        ),
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
      Tuple2(
        _1 = "Outer",
        _2 = QuickRange(
          range = Tuple4(
            _1 = 4,
            _2 = 13,
            _3 = 10,
            _4 = 3,
          )
        ),
      ),
      Tuple2(
        _1 = "Inner",
        _2 = QuickRange(
          range = Tuple4(
            _1 = 5,
            _2 = 4,
            _3 = 9,
            _4 = 5,
          )
        ),
      ),
      Tuple2(
        _1 = "inner test",
        _2 = QuickRange(
          range = Tuple4(
            _1 = 6,
            _2 = 6,
            _3 = 8,
            _4 = 7,
          )
        ),
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
      Tuple2(
        _1 = "MultipleTestsSpec",
        _2 = QuickRange(
          range = Tuple4(
            _1 = 4,
            _2 = 13,
            _3 = 14,
            _4 = 3,
          )
        ),
      ),
      Tuple2(
        _1 = "test1",
        _2 = QuickRange(
          range = Tuple4(
            _1 = 5,
            _2 = 4,
            _3 = 7,
            _4 = 5,
          )
        ),
      ),
      Tuple2(
        _1 = "test2",
        _2 = QuickRange(
          range = Tuple4(
            _1 = 8,
            _2 = 4,
            _3 = 10,
            _4 = 5,
          )
        ),
      ),
      Tuple2(
        _1 = "test3",
        _2 = QuickRange(
          range = Tuple4(
            _1 = 11,
            _2 = 4,
            _3 = 13,
            _4 = 5,
          )
        ),
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
      Tuple2(
        _1 = "level1",
        _2 = QuickRange(
          range = Tuple4(
            _1 = 5,
            _2 = 4,
            _3 = 13,
            _4 = 5,
          )
        ),
      ),
      Tuple2(
        _1 = "DeeplyNestedSpec",
        _2 = QuickRange(
          range = Tuple4(
            _1 = 4,
            _2 = 13,
            _3 = 14,
            _4 = 3,
          )
        ),
      ),
      Tuple2(
        _1 = "level2",
        _2 = QuickRange(
          range = Tuple4(
            _1 = 6,
            _2 = 6,
            _3 = 12,
            _4 = 7,
          )
        ),
      ),
      Tuple2(
        _1 = "level3",
        _2 = QuickRange(
          range = Tuple4(
            _1 = 7,
            _2 = 8,
            _3 = 11,
            _4 = 9,
          )
        ),
      ),
      Tuple2(
        _1 = "deeply nested test",
        _2 = QuickRange(
          range = Tuple4(
            _1 = 8,
            _2 = 10,
            _3 = 10,
            _4 = 11,
          )
        ),
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
      Tuple2(
        _1 = "IgnoredTestSpec",
        _2 = QuickRange(
          range = Tuple4(
            _1 = 4,
            _2 = 13,
            _3 = 11,
            _4 = 3,
          )
        ),
      ),
      Tuple2(
        _1 = "active test",
        _2 = QuickRange(
          range = Tuple4(
            _1 = 5,
            _2 = 4,
            _3 = 7,
            _4 = 5,
          )
        ),
      ),
      Tuple2(
        _1 = "ignored test",
        _2 = QuickRange(
          range = Tuple4(
            _1 = 8,
            _2 = 4,
            _3 = 10,
            _4 = 5,
          )
        ),
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
