package tests

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

import scala.meta.internal.metals.BuildTargets

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import munit.Location

class InverseDependenciesSuite extends BaseSuite {
  class Graph(val root: String) {
    val inverseDependencies: mutable.Map[String, ListBuffer[String]] =
      mutable.Map.empty[String, ListBuffer[String]]
    def dependsOn(project: String, dependency: String): this.type = {
      val dependencies =
        inverseDependencies.getOrElseUpdate(dependency, ListBuffer.empty)
      dependencies += project
      this
    }
  }
  def root(x: String): Graph = new Graph(x)
  def check(
      name: String,
      original: Graph,
      expected: String
  )(implicit loc: Location): Unit = {
    test(name) {
      val obtained = BuildTargets.inverseDependencies(
        List(new BuildTargetIdentifier(original.root)),
        { key =>
          original.inverseDependencies
            .get(key.getUri)
            .map(_.map(new BuildTargetIdentifier(_)))
        }
      )
      assertNoDiff(
        obtained.leaves.toSeq.map(_.getUri).sorted.mkString("\n"),
        expected
      )
    }
  }

  check(
    "basic",
    root("a")
      .dependsOn("b", "a"),
    "b"
  )

  check(
    "transitive",
    root("a")
      .dependsOn("b", "a")
      .dependsOn("c", "b"),
    "c"
  )

  check(
    "branch",
    root("a")
      .dependsOn("b", "a")
      .dependsOn("d", "a")
      .dependsOn("c", "b"),
    """
      |c
      |d
      |""".stripMargin
  )

  check(
    "alone",
    root("a"),
    "a"
  )

  check(
    "diamond",
    root("a")
      .dependsOn("b", "a")
      .dependsOn("c", "a")
      .dependsOn("d", "b")
      .dependsOn("d", "c"),
    "d"
  )

}
