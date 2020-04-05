package tests

import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Paths

import scala.concurrent.Future

import scala.meta.internal.metals.MetalsEnrichments._

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentPositionParams

trait ScriptsAssertions { self: BaseLspSuite =>

  def assertDefinitionAtLocation(
      file: String,
      definitionAt: String,
      expectedLocation: String,
      expectedLine: java.lang.Integer = null
  ): Future[Unit] = {

    val pos = {
      val content =
        new String(server.toPath(file).readAllBytes, StandardCharsets.UTF_8)
      val value = definitionAt.replaceAllLiterally("@@", "")
      val idx = content.onlyIndexOf(value).getOrElse {
        throw new Exception(
          s"Found multiple occurrences of '$value' in '$content'"
        )
      }
      val pipeCount = definitionAt.split("@@", -1).length - 1
      assert(pipeCount <= 1, s"Found several '|' characters in '$definitionAt'")
      val pipeIdx = Some(definitionAt.indexOf("@@"))
        .filter(_ >= 0)
        .getOrElse(0)
      content.indexToLspPosition(idx + pipeIdx)
    }

    server.server
      .definition(
        new TextDocumentPositionParams(
          new TextDocumentIdentifier(server.toPath(file).toNIO.toUri.toString),
          pos
        )
      )
      .asScala
      .map { locations =>
        val locations0 = locations.asScala
        assert(
          locations0.length == 1,
          s"Expected a single location ($expectedLocation, ${Option(expectedLine)}), got ${locations0.length} ($locations0)"
        )
        val locationUri = new URI(locations0.head.getUri)
        assert(
          locationUri.getScheme == "file",
          s"Expected file location, got URI $locationUri"
        )
        val locationPath = workspace.toNIO.relativize(Paths.get(locationUri))
        val alternativeExpectedLocation =
          if (isWindows) Some(expectedLocation.replace('/', '\\'))
          else None
        assert(
          locationPath.toString == expectedLocation || alternativeExpectedLocation
            .exists(_ == locationPath.toString),
          s"Expected location $expectedLocation${alternativeExpectedLocation
            .fold("")(loc => s"(or $loc)")}, got $locationPath"
        )
        for (expectedLine0 <- Option(expectedLine)) {
          val line = locations0.head.getRange.getStart.getLine
          assert(
            line == expectedLine0,
            s"Expected line $expectedLine0, got $line"
          )
        }
        ()
      }
  }

  def assertHoverAtPos(
      path: String,
      line: Int,
      char: Int
  ): Future[String] =
    server.server
      .hover(
        new TextDocumentPositionParams(
          new TextDocumentIdentifier(
            server.toPath(path).toNIO.toUri.toASCIIString
          ),
          new Position(line, char)
        )
      )
      .asScala
      .map { res =>
        val code = server.textContents(path)
        TestHovers.renderAsString(code, Option(res), includeRange = true)
      }
}
