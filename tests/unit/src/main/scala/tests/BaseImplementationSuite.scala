package tests

import scala.concurrent.Future

import scala.meta.internal.metals.MetalsEnrichments._

abstract class BaseImplementationSuite(name: String)
    extends BaseRangesSuite(name) {

  override def assertCheck(
      filename: String,
      edit: String,
      expected: Map[String, String],
      base: Map[String, String],
  ): Future[Unit] = {
    server.assertImplementation(
      filename,
      edit,
      expected.toMap,
      base.toMap,
    )
  }

  def checkSymbols(
      name: String,
      fileContents: String,
      expectedSymbols: String,
      scalaVersion: String = BuildInfo.scalaVersion,
  ): Unit =
    test(name) {
      val fileName = "a/src/main/scala/a/Main.scala"
      cleanWorkspace()
      for {
        _ <- initialize(
          s"""/metals.json
             |{"a":
             |  {
             |    "scalaVersion" : "$scalaVersion"
             |  }
             |}
             |/$fileName
             |${fileContents.replace("@@", "")}
          """.stripMargin
        )
        _ <- server.didOpen(fileName)
        locations <- server.implementation(fileName, fileContents)
        definitions <-
          Future.sequence(
            locations.map(location =>
              server.server.definitionResult(
                location.toTextDocumentPositionParams
              )
            )
          )
        symbols = definitions.map(_.symbol).sorted
        _ = assertNoDiff(symbols.mkString("\n"), expectedSymbols)
        _ <- server.shutdown()
      } yield ()
    }
}
