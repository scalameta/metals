package tests

import scala.meta.internal.metals.Directories

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

class FindTextInFilesSuite extends BaseLspSuite("find-text-in-jar-files") {
  test("find exact string match in .conf file inside jar") {
    val expectedUri =
      s"${workspace.resolve(Directories.dependencies)}/akka-actor_2.12-2.6.16.jar/reference.conf"
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
      locations <- server.findTextInFiles(".conf", "jvm-shutdown-hooks")
      _ = assertEquals(locations, expectedLocations)
    } yield ()
  }
}
