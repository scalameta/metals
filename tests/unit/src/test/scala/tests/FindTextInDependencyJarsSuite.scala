package tests

import scala.meta.internal.metals.Directories

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

class FindTextInDependencyJarsSuite
    extends BaseLspSuite("find-text-in-dependency-jars") {
  test("find exact string match in .conf file inside jar") {
    val expectedUri =
      workspace
        .resolve(Directories.dependencies)
        .resolve("akka-actor_2.12-2.6.16.jar")
        .resolve("reference.conf")
        .toURI
        .toString()

    val expectedJdkUri =
      workspace
        .resolve(Directories.dependencies)
        .resolve("src.zip")
        .resolve("java.base")
        .resolve("java")
        .resolve("lang")
        .resolve("String.java")
        .toURI
        .toString()

    val expectedLocations: List[Location] = List(
      new Location(
        expectedUri,
        new Range(new Position(95, 2), new Position(95, 20))
      ),
      new Location(
        expectedUri,
        new Range(new Position(1177, 40), new Position(1177, 58))
      )
    )

    val expectedJdkLocation: List[Location] = List(
      new Location(
        expectedJdkUri,
        new Range(new Position(625, 4), new Position(625, 40))
      )
    )

    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "a": {
           |    "scalaVersion": "2.12.4",
           |    "libraryDependencies": ["com.typesafe.akka::akka-actor-typed:2.6.16"]
           |  }
           |}
        """.stripMargin
      )
      locations <- server.findTextInDependencyJars(
        include = ".conf",
        pattern = "jvm-shutdown-hooks"
      )
      jdkLocations <- server.findTextInDependencyJars(
        include = ".java",
        pattern = "public String(StringBuffer buffer) {"
      )
      _ = assertEquals(locations, expectedLocations)
      _ = assertEquals(jdkLocations, expectedJdkLocation)
    } yield ()
  }
}
