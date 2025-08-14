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
      topLines: Option[Int] = None,
      filter: String => Boolean = _ => true,
  ): Unit =
    test(name, maxRetry = 3) {
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
        symbols = definitions.map(_.symbol).filter(filter).sorted
        foundSymbols = topLines.map(num => symbols.take(num)).getOrElse(symbols)
        _ = assertNoDiff(foundSymbols.mkString("\n"), expectedSymbols)
        _ <- server.shutdown()
      } yield ()
    }
}
