package tests

import scala.concurrent.Future

import munit.Location
import munit.TestOptions

abstract class BaseRenameFilesLspSuite(name: String)
    extends BaseLspSuite(name) {

  def renamed(
      name: TestOptions,
      layoutWithMarkers: String,
      fileRenames: Map[String, String],
      expectedRenames: Map[String, String],
      sourcesAreCompiled: Boolean = false,
  )(implicit loc: Location): Unit = {
    test(name) {
      cleanWorkspace()
      val layout = layoutWithMarkers.replaceAll("(<<|>>)", "")
      for {
        _ <- initialize(
          s"""/metals.json
             |${defaultMetalsJson()}
             |$layout""".stripMargin
        )
        expectedSources = calcExpectedSources(
          layoutWithMarkers,
          expectedRenames,
        )
        _ <-
          if (sourcesAreCompiled)
            Future.traverse(expectedSources.keySet)(server.didOpen)
          else Future.unit
        renamedSources <- server.willRenameFiles(
          expectedSources.keySet,
          fileRenames,
        )
        _ = assertEquals(renamedSources, expectedSources)
      } yield ()
    }
  }

  private def defaultMetalsJson(scalaVersion: Option[String] = None): String = {
    val actualScalaVersion = scalaVersion.getOrElse(BuildInfo.scalaVersion)
    s"""|{
        |  "a" : {
        |    "scalaVersion": "$actualScalaVersion"
        |  }
        |}""".stripMargin
  }

  private def calcExpectedSources(
      layoutWithMarkers: String,
      expectedRenames: Map[String, String],
  ) = {
    val renameRex = """<<([ a-zA-Z0-9_.,-/=>;]+)>>""".r
    FileLayout
      .mapFromString(layoutWithMarkers)
      .view
      .mapValues { source =>
        renameRex.replaceAllIn(
          source,
          m => {
            val toRename = m.group(1)
            assert(
              expectedRenames.contains(toRename),
              s"`expectedRenames` did not contain key `$toRename`",
            )
            expectedRenames(toRename)
          },
        )
      }
      .toMap
  }
}
