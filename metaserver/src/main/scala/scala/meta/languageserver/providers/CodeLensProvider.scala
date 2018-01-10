package scala.meta.languageserver.providers

import scala.meta.languageserver.Uri
import scala.meta.languageserver.WorkspaceCommand.SwitchPlatform
import scala.meta.languageserver.search.SymbolIndex
import com.typesafe.scalalogging.LazyLogging
import io.circe.Json
import org.langmeta.lsp.Position
import org.langmeta.lsp.Range
import org.langmeta.lsp.CodeLens
import org.langmeta.lsp.Command
import scala.meta.Symbol
import scala.meta.languageserver.ScalametaEnrichments._
import scala.meta.languageserver.WorkspaceCommand.RunTestSuite

object CodeLensProvider extends LazyLogging {
  val testSuites = List("_root_.example.UserTest#").map(Symbol.apply)
  def codeLens(uri: Uri, index: SymbolIndex): List[CodeLens] = {
    val platforms = for {
      platform <- List("JVM", "JS", "Native")
    } yield {
      CodeLens(
        Range(Position(0, 0), Position(0, 0)),
        Some(
          Command(
            platform,
            SwitchPlatform.entryName,
            Json.fromString(platform) :: Nil
          )
        ),
        None
      )
    }
    val tests = for {
      testSuite <- testSuites
      _ = pprint.log(testSuite)
      data <- index.definitionData(testSuite).toList
      _ = pprint.log(data)
      definition <- data.definition.toList
      _ = pprint.log(definition)
      if definition.uri == uri.value
      range <- definition.range.toList
      _ = pprint.log(range)
    } yield {
      CodeLens(
        range.toRange,
        Some(
          Command(
            "Run test suite",
            RunTestSuite.entryName,
            Json.fromString(testSuite.syntax) :: Nil
          )
        ),
        None
      )
    }

    platforms ++ tests
  }
}
