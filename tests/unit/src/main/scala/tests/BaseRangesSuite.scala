package tests

import scala.concurrent.Future

import munit.Location
import munit.TestOptions

abstract class BaseRangesSuite(name: String) extends BaseLspSuite(name) {

  protected def libraryDependencies: List[String] = Nil

  def assertCheck(
      filename: String,
      edit: String,
      expected: Map[String, String],
      base: Map[String, String],
  ): Future[Unit]

  def check(
      name: TestOptions,
      input: String,
      scalaVersion: Option[String] = None,
      additionalLibraryDependencies: List[String] = Nil,
      scalacOptions: List[String] = Nil,
      customMetalsJson: Option[String] = None,
      additionalEdits: () => Future[Unit] = () => Future.successful(()),
  )(implicit
      loc: Location
  ): Unit = {
    val files = FileLayout.mapFromString(input)
    val allReferenceLocations = FileLayout.queriesFromFiles(files)

    val expected = files.map { case (fileName, code) =>
      fileName -> code.replaceAll("@@", "")
    }
    val base = files.map { case (fileName, code) =>
      fileName -> code.replaceAll("(<<|>>|@@)", "")
    }
    val actualScalaVersion = scalaVersion.getOrElse(BuildInfo.scalaVersion)
    val metalsJson =
      customMetalsJson.getOrElse(
        s"""|{"a":
            |  {
            |    "scalaVersion" : "$actualScalaVersion",
            |    "libraryDependencies": ${toJsonArray(libraryDependencies ++ additionalLibraryDependencies)},
            |    "scalacOptions": ${toJsonArray(scalacOptions)}
            |  }
            |}
            |""".stripMargin
      )

    test(name) {
      cleanWorkspace()
      for {
        _ <- initialize(
          s"""/metals.json
             |$metalsJson
             |${input
              .replaceAll("(<<|>>|@@)", "")}""".stripMargin
        )
        _ <- Future.sequence(
          files.map(file => server.didOpen(s"${file._1}"))
        )
        _ <- additionalEdits()
        allChecks = for {
          query <- allReferenceLocations
        } yield () =>
          assertCheck(
            query.filename,
            query.query.replaceAll("(<<|>>)", ""),
            expected,
            base,
          )
        _ <- MetalsTestEnrichments.orderly(allChecks)
        _ <- server.shutdown()
      } yield ()
    }
  }
}
