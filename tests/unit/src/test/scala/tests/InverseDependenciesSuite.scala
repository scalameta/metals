package tests

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

import scala.meta.internal.metals.BuildTargets

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import munit.Location
import munit.TestOptions

class InverseDependenciesSuite extends BaseSuite {

  class Graph(val root: String) {
    val inverseDependencies: mutable.Map[String, ListBuffer[String]] =
      mutable.Map.empty

    def dependsOn(project: String, dependency: String): Graph = {
      val dependencies =
        inverseDependencies.getOrElseUpdate(dependency, ListBuffer.empty)
      dependencies += project
      this
    }
  }

  def root(x: String): Graph = new Graph(x)

  def check(
      name: TestOptions,
      byNameOriginal: => Graph,
      expectedLeaves: List[String],
      expectedAll: List[String] = List.empty,
  )(implicit loc: Location): Unit = {
    test(name) {
      val original = byNameOriginal
      val obtained = BuildTargets.inverseDependencies(
        List(new BuildTargetIdentifier(original.root)),
        { key =>
          original.inverseDependencies
            .get(key.getUri)
            .map(_.map(new BuildTargetIdentifier(_)).toSeq)
        },
      )

      assertEquals(
        obtained.leaves.toList.map(_.getUri).sorted,
        expectedLeaves,
        "leaves don't match",
      )

      assertEquals(
        obtained.visited.toList.map(_.getUri).sorted,
        expectedAll,
        "visited don't match",
      )
    }
  }

  check(
    "basic",
    root("a")
      .dependsOn("b", "a"),
    expectedLeaves = List("b"),
    expectedAll = List("a", "b"),
  )

  check(
    "transitive",
    root("a")
      .dependsOn("b", "a")
      .dependsOn("c", "b"),
    expectedLeaves = List("c"),
    expectedAll = List("a", "b", "c"),
  )

  check(
    "branch",
    root("a")
      .dependsOn("b", "a")
      .dependsOn("d", "a")
      .dependsOn("c", "b"),
    expectedLeaves = List("c", "d"),
    expectedAll = List("a", "b", "c", "d"),
  )

  check(
    "alone",
    root("a"),
    expectedLeaves = List("a"),
    expectedAll = List("a"),
  )

  check(
    "diamond",
    root("a")
      .dependsOn("b", "a")
      .dependsOn("c", "a")
      .dependsOn("d", "b")
      .dependsOn("d", "c"),
    expectedLeaves = List("d"),
    expectedAll = List("a", "b", "c", "d"),
  )

  /**
   * z aggregates all modules
   *
   *    a  a      a
   *    ^  ^      ^
   *    |  |      |
   *    b  |      c
   *    |  |    ^ ^ ^
   *    |  |    | | |
   *    |  |    | d e
   *    |  |    | |
   *    z  z     z
   * }}}
   */
  check(
    "aggregate",
    root("a")
      .dependsOn("z", "a")
      .dependsOn("b", "a")
      .dependsOn("z", "b")
      .dependsOn("c", "a")
      .dependsOn("z", "c")
      .dependsOn("d", "c")
      .dependsOn("z", "d")
      .dependsOn("e", "c"),
    expectedLeaves = List("e", "z"),
    expectedAll = List("a", "b", "c", "d", "e", "z"),
  )
}
