package tests

import scala.meta._
import scala.meta.internal.metals.DocumentSymbolProvider
import org.eclipse.lsp4j.DocumentSymbol

/**
 * TODO(gabro)
 */
object DocumentSymbolSuite extends DirectoryExpectSuite("documentSymbol") {
  override def testCases(): List[ExpectTestCase] = {
    input.scalaFiles.filter(_.file.toString.endsWith("AnonymousClasses.scala")).map { file =>
      ExpectTestCase(
        file, { () =>
          val source = file.input.parse[Source].get
          val sb = new StringBuilder
          val documentSymbols = DocumentSymbolProvider.documentSymbols(source)

          def printDocumentSymbols(symbols: List[DocumentSymbol]): Unit = {
            if (symbols.nonEmpty) {
              val kinds = symbols.map(_.getKind).mkString("/*", ", ", "*/")
              sb.append(kinds)
            }
          }

          file.input.text.lines.zipWithIndex.foreach { case (line, lineNo) =>
            val symbols = documentSymbols.filter(_.getRange.getStart.getLine == lineNo)
            printDocumentSymbols(symbols)
            sb.append(line).append("\n")
          }
          sb.toString()

        }
      )
    }
  }

}
