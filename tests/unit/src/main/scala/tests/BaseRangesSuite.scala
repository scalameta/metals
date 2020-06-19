package tests

import scala.concurrent.Future

import munit.Location

abstract class BaseRangesSuite(name: String) extends BaseLspSuite(name) {

  def assertCheck(
      filename: String,
      edit: String,
      expected: Map[String, String],
      base: Map[String, String]
  ): Future[Unit]

  def check(name: String, input: String, scalaVersion: Option[String] = None)(
      implicit loc: Location
  ): Unit = {
    val files = FileLayout.mapFromString(input)
    val (filename, edit) = files
      .find(_._2.contains("@@"))
      .map {
        case (fileName, code) =>
          (fileName, code.replaceAll("(<<|>>)", ""))
      }
      .getOrElse {
        throw new IllegalArgumentException(
          "No `@@` was defined that specifies cursor position"
        )
      }
    val expected = files.map {
      case (fileName, code) =>
        fileName -> code.replaceAll("@@", "")
    }
    val base = files.map {
      case (fileName, code) =>
        fileName -> code.replaceAll("(<<|>>|@@)", "")
    }

    val actualScalaVersion = scalaVersion.getOrElse(BuildInfo.scalaVersion)
    test(name) {
      cleanWorkspace()
      for {
        _ <- server.initialize(
          s"""/metals.json
             |{"a":
             |  {
             |    "scalaVersion" : "$actualScalaVersion",
             |    "compilerPlugins": [
             |      "org.scalamacros:::paradise:2.1.1"
             |    ],
             |    "libraryDependencies": [
             |      "org.scalatest::scalatest:3.0.5",
             |      "io.circe::circe-generic:0.12.0"
             |    ]
             |  }
             |}
             |${input
            .replaceAll("(<<|>>|@@)", "")}""".stripMargin
        )
        _ <- Future.sequence(
          files.map(file => server.didOpen(s"${file._1}"))
        )
        _ <- assertCheck(filename, edit, expected, base)
      } yield ()
    }
  }
}
