package tests

import scala.meta.internal.inputs._
import scala.meta.internal.metals.WorkspaceSymbolProvider
import scala.meta.internal.mtags.Mtags
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.mtags.Semanticdbs

class WorkspaceSymbolExpectSuite
    extends DirectoryExpectSuite("workspace-symbol") {
  def testCases(): List[ExpectTestCase] = {
    input.allFiles.map { file =>
      ExpectTestCase(
        file,
        { () =>
          val input = file.input
          val mtags0 = Mtags.allToplevels(input)
          val symtab0 = mtags0.symbols.map(i => i.symbol -> i).toMap
          val mtags = mtags0.copy(
            occurrences = mtags0.occurrences.filter { occ =>
              WorkspaceSymbolProvider.isRelevantKind(symtab0(occ.symbol).kind)
            }
          )
          val obtained = Semanticdbs.printTextDocument(mtags)
          val isException = Set(
            "example/nested/LocalClass#LocalClass#"
          )
          val unknownSymbols = mtags.occurrences.collect {
            case occ
                if symtab.info(occ.symbol).isEmpty && !isException(
                  occ.symbol
                ) =>
              val pos = input.toPosition(occ)
              pos.formatMessage("error", s"unknown symbol: ${occ.symbol}")
          }
          if (unknownSymbols.nonEmpty) {
            fail(unknownSymbols.mkString("\n"))
          }
          obtained
        }
      )
    }
  }
}
