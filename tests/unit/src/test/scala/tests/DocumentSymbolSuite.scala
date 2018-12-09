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
      .filter(f => f.file.toString.endsWith("AnonymousClasses.scala") || f.file.toString.endsWith("Comments.scala"))
      .map { file =>
        ExpectTestCase(
          file, { () =>
            val sb = new StringBuilder
            val documentSymbols =
              documentSymbolProvider.documentSymbols(file.file)

            def printDocumentSymbols(symbols: List[DocumentSymbol], line: String): Unit = {
              if (symbols.nonEmpty) {
                val kinds = symbols.map(_.getKind.toString.padTo(9, ' ')).mkString("/*", ", ", "*/")
                sb.append(kinds).append(line)
              } else if (line.nonEmpty) {
                sb.append(" " * 13).append(line)
              } else {
                sb.append(line)
              }
              sb.append('\n')
            }

            file.input.text.lines.zipWithIndex.foreach {
              case (line, lineNo) =>
                val symbols =
                  documentSymbols.filter(_.getRange.getStart.getLine == lineNo)
                printDocumentSymbols(symbols, line)
            }
            sb.toString()

          }
        )
      }
  }

}
