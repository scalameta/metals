package tests

import scala.meta.internal.metals.TextEdits
import org.eclipse.{lsp4j => l}
import scala.concurrent.Future
import scala.meta.inputs.Input
import scala.meta.inputs.Position
import scala.meta.internal.mtags.MtagsEnrichments._

class BaseTypeDefinitionLspSuite extends BaseLspSuite("typeDefinition") {
  def check(
      name: String
  )(query: String, expectedLocs: List[String] = Nil): Unit = {
    testAsync(name) {
      cleanWorkspace()
      val code =
        query
          .replaceAll("(@@)?(<<)?(>>)?", "")
          .replaceAll("""\/\*[^\*]+\*\/""", "")
      val files = query.lines
        .filter(_.startsWith("/"))
        .filter(_.filter(!_.isWhitespace) != "/metals.json")
        .map(_.stripPrefix("/"))

      for {
        _ <- server.initialize(
          s"""${code.trim}""".stripMargin
        )
        _ <- Future.sequence(files.map(server.didOpen))
        expected = expectedLocs.flatMap(
          TestingServer.locationFromString(_, workspace)
        )
        _ <- server.assertTypeDefinition(
          queryStr = query,
          expectedLocs = expected,
          root = workspace
        )
        _ = assertNoDiagnostics()
        _ = if (!server.server.isInitialized.get())
          fail("Build tool not initialized")
      } yield ()
    }
  }

  def prepareDefinition(original: String): String =
    original.replaceAll("(<<)?(>>)?", "")

  def locationsToCode(
      code: String,
      uri: String,
      offsetRange: l.Range,
      locations: List[l.Location]
  ): String = {
    val edits = locations.flatMap { loc =>
      {
        val location = new l.Location(
          workspace.toURI.relativize(new java.net.URI(loc.getUri)).toString,
          loc.getRange
        )
        if (location.getUri == uri) {
          List(
            new l.TextEdit(
              new l.Range(
                location.getRange.getStart,
                location.getRange.getStart
              ),
              "<<"
            ),
            new l.TextEdit(
              new l.Range(
                location.getRange.getEnd,
                location.getRange.getEnd
              ),
              ">>"
            )
          )
        } else {
          val filename = location.getUri
          val comment = s"/*$filename*/"
          if (code.contains(comment)) {
            Nil
          } else {
            List(new l.TextEdit(offsetRange, comment))
          }
        }
      }
    }
    TextEdits.applyEdits(code, edits)
  }

  def obtainedAndExpected(original: String, uri: String): Future[String] = {
    val code = prepareDefinition(original)
    val offset = code.indexOf("@@")
    if (offset < 0) fail("@@ missing")

    val offsetRange = Position.Range(Input.String(code), offset, offset).toLSP
    val locationsF = server.typeDefinition(uri, code)
    locationsF.map(l => locationsToCode(code, uri, offsetRange, l))
  }

  def checkTypeDefinition(
      query: String,
      name: String = "Main.scala"
  ): Future[Unit] = {
    val obtainedF =
      obtainedAndExpected(query, workspace.toURI.resolve(name).toString)

    val expected = query
    obtainedF.map(o => assertNoDiff(o, expected))
  }
}
