package tests

import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.DocumentSymbolProvider
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
          // val sb = new StringBuilder
          val documentSymbols =
            documentSymbolProvider.documentSymbols(file.file)

          def toTextDocument(
              symbols: List[DocumentSymbol]
          ): s.TextDocument = {
            def toSymbolOccurrence(
                sym: DocumentSymbol
            ): s.SymbolOccurrence = s.SymbolOccurrence(
              range = Some(
                new s.Range(
                  sym.getRange.getStart.getLine,
                  sym.getRange.getStart.getCharacter,
                  sym.getRange.getStart.getLine,
                  sym.getRange.getStart.getCharacter
                )
              ),
              symbol = sym.getName,
              role = s.SymbolOccurrence.Role.DEFINITION
            )
            s.TextDocument()
              .withLanguage(s.Language.SCALA)
              .withText(file.input.text)
              .withOccurrences(symbols.map(toSymbolOccurrence))
          }

          Semanticdbs.printTextDocument(toTextDocument(documentSymbols))
        }
      )
    }
  }

}
