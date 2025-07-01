package tests.testProvider

import java.nio.file.Paths

import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.testProvider.FullyQualifiedName
import scala.meta.internal.metals.testProvider.frameworks.ScalatestStyle
import scala.meta.internal.metals.testProvider.frameworks.ScalatestTestFinder
import scala.meta.io.AbsolutePath

import munit.FunSuite
import munit.Location
import munit.TestOptions
import tests.QuickRange
import tests.TreeUtils

class ScalatestFinderSuite extends FunSuite {

  check(
    "any-fun-suite",
    """|import org.scalatest.funsuite.AnyFunSuite
       |import org.scalatest.Tag
       |
       |class SetSuite extends AnyFunSuite {
       |
       |  test("An empty Set should have size 0", Tag.apply("customTag")) {
       |    assert(Set.empty.size == 0)
       |  }
       |
       |  test("Invoking head on an empty Set should produce NoSuchElementException") {
       |    assertThrows[NoSuchElementException] {
       |      Set.empty.head
       |    }
       |  }
       |
       |  ignore("An empty Set should have size 1") {
       |    assert(Set.empty.size == 1)
       |  }
       |}
       |""".stripMargin,
    FullyQualifiedName("SetSuite"),
    Set(
      ("An empty Set should have size 0", QuickRange(5, 2, 5, 65)),
      (
        "Invoking head on an empty Set should produce NoSuchElementException",
        QuickRange(9, 2, 9, 77),
      ),
      ("An empty Set should have size 1", QuickRange(15, 2, 15, 43)),
    ),
    ScalatestStyle.AnyFunSuite,
  )

  check(
    "any-word-spec",
    """|class SetSpec extends AnyWordSpec {
       |
       |  "A Set" when {
       |    "empty" should {
       |      "have size 0" in {
       |        assert(Set.empty.size == 0)
       |      }
       |
       |      "produce NoSuchElementException when head is invoked" in {
       |        assertThrows[NoSuchElementException] {
       |          Set.empty.head
       |        }
       |      }
       |
       |      "have size 1" ignore {
       |        assert(Set.empty.size == 1)
       |      }
       |
       |    }
       |  }
       |}
       |""".stripMargin,
    FullyQualifiedName("SetSpec"),
    Set(
      ("A Set when empty should have size 0", QuickRange(4, 6, 4, 19)),
      (
        "A Set when empty should produce NoSuchElementException when head is invoked",
        QuickRange(8, 6, 8, 59),
      ),
      ( // this is ignored test
        "A Set when empty should have size 1",
        QuickRange(14, 6, 14, 19),
      ),
    ),
    ScalatestStyle.AnyWordSpec,
  )

  check(
    "async-word-spec",
    """|class AsyncSetSpec extends AsyncWordSpec {
       |
       |  "A Set" when {
       |    "empty" should {
       |      "have size 0" in {
       |        assert(Set.empty.size == 0)
       |      }
       |
       |      "produce NoSuchElementException when head is invoked" in {
       |        assertThrows[NoSuchElementException] {
       |          Set.empty.head
       |        }
       |      }
       |
       |      "have size 1" ignore {
       |        assert(Set.empty.size == 1)
       |      }
       |
       |    }
       |  }
       |}
       |""".stripMargin,
    FullyQualifiedName("AsyncSetSpec"),
    Set(
      ("A Set when empty should have size 0", QuickRange(4, 6, 4, 19)),
      (
        "A Set when empty should produce NoSuchElementException when head is invoked",
        QuickRange(8, 6, 8, 59),
      ),
      ( // this is ignored test
        "A Set when empty should have size 1",
        QuickRange(14, 6, 14, 19),
      ),
    ),
    ScalatestStyle.AnyWordSpec,
  )

  check(
    "fixture-async-word-spec",
    """|class FixtureAsyncSetSpec extends FixtureAsyncWordSpec {
       |
       |  "A Set" when {
       |    "empty" should {
       |      "have size 0" in {
       |        assert(Set.empty.size == 0)
       |      }
       |
       |      "produce NoSuchElementException when head is invoked" in {
       |        assertThrows[NoSuchElementException] {
       |          Set.empty.head
       |        }
       |      }
       |
       |      "have size 1" ignore {
       |        assert(Set.empty.size == 1)
       |      }
       |
       |    }
       |  }
       |}
       |""".stripMargin,
    FullyQualifiedName("FixtureAsyncSetSpec"),
    Set(
      ("A Set when empty should have size 0", QuickRange(4, 6, 4, 19)),
      (
        "A Set when empty should produce NoSuchElementException when head is invoked",
        QuickRange(8, 6, 8, 59),
      ),
      ( // this is ignored test
        "A Set when empty should have size 1",
        QuickRange(14, 6, 14, 19),
      ),
    ),
    ScalatestStyle.AnyWordSpec,
  )

  check(
    "any-flat-spec",
    """|import org.scalatest.flatspec.AnyFlatSpec
       |
       |class FlatSpec extends AnyFlatSpec {
       |
       |  "An empty Set" should "have size 0" in {
       |    assert(Set.empty.size == 0)
       |  }
       |
       |  it should "produce NoSuchElementException when head is invoked" in {
       |    assertThrows[NoSuchElementException] {
       |      Set.empty.head
       |    }
       |  }
       |
       |  ignore should "have size 1" in {
       |    assert(Set.empty.size == 1)
       |  }
       |
       |  behavior of "Non-empty Set"
       |
       |  it should "have size greater than 0" in {
       |    assert(Set(1).size > 0)
       |  }
       |}
       |""".stripMargin,
    FullyQualifiedName("FlatSpec"),
    Set(
      ("An empty Set should have size 1", QuickRange(14, 2, 14, 29)),
      (
        "An empty Set should produce NoSuchElementException when head is invoked",
        QuickRange(8, 2, 8, 65),
      ),
      ("An empty Set should have size 0", QuickRange(4, 2, 4, 37)),
      (
        "Non-empty Set should have size greater than 0",
        QuickRange(20, 2, 20, 38),
      ),
    ),
    ScalatestStyle.AnyFlatSpec,
  )

  check(
    "any-fun-spec",
    """|import org.scalatest.funspec.AnyFunSpec
       |
       |class FunSpec extends AnyFunSpec {
       |
       |  describe("A Set") {
       |    describe("when empty") {
       |      it("should have size 0") {
       |        assert(Set.empty.size == 0)
       |      }
       |
       |      it("should produce NoSuchElementException when head is invoked") {
       |        assertThrows[NoSuchElementException] {
       |          Set.empty.head
       |        }
       |      }
       |    }
       |  }
       |}
       |""".stripMargin,
    FullyQualifiedName("FunSpec"),
    Set(
      ("A Set when empty should have size 0", QuickRange(6, 6, 6, 30)),
      (
        "A Set when empty should produce NoSuchElementException when head is invoked",
        QuickRange(10, 6, 10, 70),
      ),
    ),
    ScalatestStyle.AnyFunSpec,
  )

  check(
    "any-free-spec",
    """|import org.scalatest.freespec.AnyFreeSpec
       |
       |class FreeSpec extends AnyFreeSpec {
       |
       |  "A Set" - {
       |    "when empty" - {
       |      "should have size 0" in {
       |        assert(Set.empty.size == 0)
       |      }
       |
       |      "should produce NoSuchElementException when head is invoked" in {
       |        assertThrows[NoSuchElementException] {
       |          Set.empty.head
       |        }
       |      }
       |
       |      "should have size 1" ignore {
       |        assert(Set.empty.size == 1)
       |      }
       |    }
       |  }
       |}
       |""".stripMargin,
    FullyQualifiedName("FreeSpec"),
    Set(
      ("A Set when empty should have size 0", QuickRange(6, 6, 6, 26)),
      (
        "A Set when empty should produce NoSuchElementException when head is invoked",
        QuickRange(10, 6, 10, 66),
      ),
      ( // this is ignored test
        "A Set when empty should have size 1",
        QuickRange(16, 6, 16, 26),
      ),
    ),
    ScalatestStyle.AnyFreeSpec,
  )

  check(
    "any-prop-spec",
    """|import org.scalatest.propspec.AnyPropSpec
       |
       |class PropSpec extends AnyPropSpec {
       |
       |  property("an empty Set should have size 0") {
       |    assert(Set.empty.size == 0)
       |  }
       |
       |  property(
       |    "invoking head on an empty set should produce NoSuchElementException"
       |  ) {
       |    assertThrows[NoSuchElementException] {
       |      Set.empty.head
       |    }
       |  }
       |
       |  ignore("an empty Set should have size 1") {
       |    assert(Set.empty.size == 1)
       |  }
       |}
       |
       |""".stripMargin,
    FullyQualifiedName("PropSpec"),
    Set(
      ("an empty Set should have size 0", QuickRange(4, 2, 4, 45)),
      (
        "invoking head on an empty set should produce NoSuchElementException",
        QuickRange(8, 2, 10, 3),
      ),
      ( // this is ignored test
        "an empty Set should have size 1",
        QuickRange(16, 2, 16, 43),
      ),
    ),
    ScalatestStyle.AnyPropSpec,
  )

  check(
    "any-feature-spec",
    """|import org.scalatest.featurespec.AnyFeatureSpec
       |
       |class FeatureSpec extends AnyFeatureSpec {
       |  Feature("A test feature") {
       |    Scenario("A test scenario") {
       |      assert(1 + 1 == 2)
       |    }
       |    Scenario("Another test scenario") {
       |      assert(2 + 2 == 5)
       |    }
       |  }
       |}
       |""".stripMargin,
    FullyQualifiedName("FeatureSpec"),
    Set(
      ("A test feature A test scenario", QuickRange(4, 4, 4, 31)),
      ("A test feature Another test scenario", QuickRange(7, 4, 7, 37)),
    ),
    ScalatestStyle.AnyFeatureSpec,
  )

  check(
    "any-async-feature-spec",
    """|import org.scalatest.featurespec.AsyncFeatureSpecLike
       |
       |class AsyncFeatureSpec extends AsyncFeatureSpecLike {
       |  Feature("A test feature") {
       |    Scenario("A test scenario") {
       |      assert(1 + 1 == 2)
       |    }
       |    Scenario("Another test scenario") {
       |      assert(2 + 2 == 5)
       |    }
       |  }
       |}
       |""".stripMargin,
    FullyQualifiedName("AsyncFeatureSpec"),
    Set(
      ("A test feature A test scenario", QuickRange(4, 4, 4, 31)),
      ("A test feature Another test scenario", QuickRange(7, 4, 7, 37)),
    ),
    ScalatestStyle.AnyFeatureSpec,
  )

  def check(
      name: TestOptions,
      sourceText: String,
      suite: FullyQualifiedName,
      expected: Set[(String, QuickRange)],
      style: ScalatestStyle,
      scalaVersion: String = BuildInfo.scala213,
  )(implicit loc: Location): Unit =
    test(name) {
      val filename = "TestFile.scala"
      val path = AbsolutePath(Paths.get(filename))

      val (buffers, trees) = TreeUtils.getTrees(scalaVersion)
      buffers.put(path, sourceText)

      val result = trees
        .get(path)
        .map(tree =>
          ScalatestTestFinder.findTestLocations(path, style, tree, suite).toSet
        )
        .getOrElse(Set.empty)

      val expected0 = expected.map { case (name, range) => (name, range.toLsp) }
      val obtained =
        result.map(t => (t.name, t.location.getRange))

      assertEquals(obtained, expected0)
    }

}
