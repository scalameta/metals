package tests.navigation

import scala.meta.internal.metals.DocumentSymbolProvider
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.{semanticdb => s}
import tests.DirectoryExpectSuite
import tests.TestingTrees
import tests.ExpectTestCase
import tests.MetalsTestEnrichments._

/**
 * Checks the positions of document symbols inside a document
 */
class DocumentSymbolSuite extends DirectoryExpectSuite("documentSymbol") {
  val documentSymbolProvider = new DocumentSymbolProvider(TestingTrees())

  override def testCases(): List[ExpectTestCase] = {
    input.scalaFiles.map { file =>
      ExpectTestCase(
        file, { () =>
          val documentSymbols = documentSymbolProvider
            .documentSymbols(file.file)
            .asScala
          val flatSymbols =
            documentSymbols.toSymbolInformation(file.file.toURI.toString)
          val textDocument = s.TextDocument(
            schema = s.Schema.SEMANTICDB4,
            language = s.Language.SCALA,
            text = file.input.text,
            occurrences = flatSymbols.map(_.toSymbolOccurrence)
          )

          Semanticdbs.printTextDocument(textDocument)
        }
      )
    }
  }

}
