package tests

import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.DocumentSymbolProvider
import org.eclipse.lsp4j.DocumentSymbol

/**
 * TODO(gabro)
 */
object DocumentSymbolSuite extends DirectoryExpectSuite("documentSymbol") {
  val documentSymbolProvider = new DocumentSymbolProvider(new Buffers())
  override def testCases(): List[ExpectTestCase] = {
    input.scalaFiles
      .filter(_.file.toString.endsWith("AnonymousClasses.scala"))
      .map { file =>
        ExpectTestCase(
          file, { () =>
            val sb = new StringBuilder
            val documentSymbols =
              documentSymbolProvider.documentSymbols(file.file)

            def printDocumentSymbols(symbols: List[DocumentSymbol]): Unit = {
              if (symbols.nonEmpty) {
                val kinds = symbols.map(_.getKind).mkString("/*", ", ", "*/")
                sb.append(kinds)
              }
            }

            file.input.text.lines.zipWithIndex.foreach {
              case (line, lineNo) =>
                val symbols =
                  documentSymbols.filter(_.getRange.getStart.getLine == lineNo)
                printDocumentSymbols(symbols)
                sb.append(line).append("\n")
            }
            sb.toString()

          }
        )
      }
  }

}
