package tests

import scala.meta.dialects
import scala.meta.internal.inputs._
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
          val mtags0 = Mtags.allToplevels(input, dialects.Scala213)
          val symtab0 = mtags0.symbols.map(i => i.symbol -> i).toMap
          val mtags = mtags0.copy(
            occurrences = mtags0.occurrences.filter { occ =>
              symtab0(occ.symbol).kind.isRelevantKind
            }
          )
          val obtained = Semanticdbs.printTextDocument(mtags)
          val fileSymtab = symtab(file.file)
          val unknownSymbols = mtags.occurrences.collect {
            case occ if fileSymtab.info(occ.symbol).isEmpty =>
              val pos = input.toPosition(occ)
              pos.formatMessage("error", s"unknown symbol: ${occ.symbol}")
          }
          if (unknownSymbols.nonEmpty) {
            fail(unknownSymbols.mkString("\n"))
          }
          obtained
        },
      )
    }
  }
}
