package tests

import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.DocumentSymbolProvider
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.{semanticdb => s}
import org.eclipse.lsp4j.DocumentSymbol

/**
 * Checks the positions of document symbols inside a document
 */
object DocumentSymbolSuite extends DirectoryExpectSuite("documentSymbol") {
  val documentSymbolProvider = new DocumentSymbolProvider(new Buffers())

  override def testCases(): List[ExpectTestCase] = {
    input.scalaFiles.map { file =>
      ExpectTestCase(
        file, { () =>
          val documentSymbols =
            documentSymbolProvider.documentSymbols(file.file)

          def toTextDocument(
              symbols: List[DocumentSymbol]
          ): s.TextDocument = {
            s.TextDocument(
              schema = s.Schema.SEMANTICDB4,
              language = s.Language.SCALA,
              text = file.input.text,
              occurrences = symbols.map(_.toSymbolOccurrence)
            )
          }

          Semanticdbs.printTextDocument(toTextDocument(documentSymbols))
        }
      )
    }
  }

}
