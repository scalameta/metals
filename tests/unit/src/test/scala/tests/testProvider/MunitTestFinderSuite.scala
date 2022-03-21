package tests.testProvider

import java.nio.file.Paths

import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.ScalaVersionSelector
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.testProvider.FullyQualifiedName
import scala.meta.internal.metals.testProvider.TestCaseEntry
import scala.meta.internal.metals.testProvider.frameworks.MunitTestFinder
import scala.meta.internal.parsing.Trees
import scala.meta.io.AbsolutePath

import tests.BuildInfo
import tests.QuickLocation

class MunitTestFinderSuite extends munit.FunSuite {

  val filename = "MunitTestSuite.scala"
  val path: AbsolutePath = AbsolutePath(Paths.get(filename))

  check("direct-test-call")(
    s"""|package a
        |class MunitTestSuite extends munit.FunSuite {
        |
        |  test("test-1") {}
        |
        |  test("test-2".ignore) {}
        |
        |  test("test-3".only) { assert(false) }
        |}
        |""".stripMargin,
    Vector(
      TestCaseEntry(
        "test-1",
        QuickLocation(
          path.toURI.toString(),
          (3, 2, 3, 6)
        ).toLsp
      ),
      TestCaseEntry(
        "test-2",
        QuickLocation(
          path.toURI.toString(),
          (5, 2, 5, 6)
        ).toLsp
      ),
      TestCaseEntry(
        "test-3",
        QuickLocation(
          path.toURI.toString(),
          (7, 2, 7, 6)
        ).toLsp
      )
    )
  )

  check("helper-function")(
    s"""|package a
        |class MunitTestSuite extends munit.FunSuite {
        |
        |  check("check-1", 2, 2)
        |
        |  checkBraceless("check-2-braceless", 2, 2)
        |
        |  checkCurried("check-3-curried", 2, 2)
        |
        |  def check(name: String, n1: Int, n2: Int = 1) = {
        |    test(name) {
        |      println(name)
        |      assertEquals(n1, n2)
        |    }
        |  }
        |
        |  def checkBraceless(name: String, n1: Int, n2: Int = 1) =
        |    test(name) {
        |      println(name)
        |      assertEquals(n1, n2)
        |    }
        |
        |  def checkCurried(name: String)(n1: Int, n2: Int = 1) =
        |    test(name) {
        |      println(name)
        |      assertEquals(n1, n2)
        |    }
        |}
        |""".stripMargin,
    Vector(
      TestCaseEntry(
        "check-1",
        QuickLocation(
          path.toURI.toString(),
          (3, 2, 3, 7)
        ).toLsp
      ),
      TestCaseEntry(
        "check-2-braceless",
        QuickLocation(
          path.toURI.toString(),
          (5, 2, 5, 16)
        ).toLsp
      ),
      TestCaseEntry(
        "check-3-curried",
        QuickLocation(
          path.toURI.toString(),
          (7, 2, 7, 14)
        ).toLsp
      )
    )
  )

  def check(name: munit.TestOptions)(
      sourceText: String,
      expected: Vector[TestCaseEntry],
      fullyQualifiedName: String = "a.MunitTestSuite",
      scalaVersion: String = BuildInfo.scalaVersion
  )(implicit loc: munit.Location): Unit = test(name) {
    val (buffers, munitTestFinder) = init(scalaVersion)
    buffers.put(path, sourceText)

    val tests =
      munitTestFinder.findTests(path, FullyQualifiedName(fullyQualifiedName))
    assertEquals(tests, expected)
  }

  def init(scalaVersion: String): (Buffers, MunitTestFinder) = {
    val buffers = Buffers()
    val buildTargets = new BuildTargets()
    val selector = new ScalaVersionSelector(
      () => UserConfiguration(fallbackScalaVersion = Some(scalaVersion)),
      buildTargets
    )
    val trees = new Trees(buildTargets, buffers, selector)
    val munitTestFinder = new MunitTestFinder(trees)
    (buffers, munitTestFinder)
  }

}
