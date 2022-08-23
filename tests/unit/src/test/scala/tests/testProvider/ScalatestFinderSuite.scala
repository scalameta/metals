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
       |}
       |""".stripMargin,
    FullyQualifiedName("SetSuite"),
    Vector(
      ("An empty Set should have size 0", QuickRange(5, 2, 5, 65)),
      (
        "Invoking head on an empty Set should produce NoSuchElementException",
        QuickRange(9, 2, 9, 77),
      ),
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
       |    }
       |  }
       |}
       |""".stripMargin,
    FullyQualifiedName("SetSpec"),
    Vector(
      ("A Set when empty should have size 0", QuickRange(4, 6, 4, 19)),
      (
        "A Set when empty should produce NoSuchElementException when head is invoked",
        QuickRange(8, 6, 8, 59),
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
       |}
       |""".stripMargin,
    FullyQualifiedName("FlatSpec"),
    Vector(
      (
        "An empty Set should produce NoSuchElementException when head is invoked",
        QuickRange(8, 2, 8, 65),
      ),
      ("An empty Set should have size 0", QuickRange(4, 2, 4, 37)),
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
    Vector(
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
       |    }
       |  }
       |}
       |""".stripMargin,
    FullyQualifiedName("FreeSpec"),
    Vector(
      ("A Set when empty should have size 0", QuickRange(6, 6, 6, 26)),
      (
        "A Set when empty should produce NoSuchElementException when head is invoked",
        QuickRange(10, 6, 10, 66),
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
       |}
       |
       |""".stripMargin,
    FullyQualifiedName("PropSpec"),
    Vector(
      ("an empty Set should have size 0", QuickRange(4, 2, 4, 45)),
      (
        "invoking head on an empty set should produce NoSuchElementException",
        QuickRange(8, 2, 10, 3),
      ),
    ),
    ScalatestStyle.AnyPropSpec,
  )

  def check(
      name: TestOptions,
      sourceText: String,
      suite: FullyQualifiedName,
      expected: Vector[(String, QuickRange)],
      style: ScalatestStyle,
      scalaVersion: String = BuildInfo.scala213,
  )(implicit loc: Location): Unit =
    test(name) {
      val filename = "TestFile.scala"
      val path = AbsolutePath(Paths.get(filename))

      val (buffers, trees) = TreeUtils.getTrees(scalaVersion)
      buffers.put(path, sourceText)

      val result = for {
        tree <- trees.get(path)
      } yield ScalatestTestFinder.findTestLocations(path, style, tree, suite)

      val expected0 = expected.map { case (name, range) => (name, range.toLsp) }
      val obtained =
        result.getOrElse(Vector.empty).map(t => (t.name, t.location.getRange))

      assertEquals(obtained, expected0)
    }

}
