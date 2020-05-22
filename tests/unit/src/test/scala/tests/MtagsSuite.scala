package tests

import scala.meta.internal.inputs._
import scala.meta.internal.mtags.Mtags
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.semanticdb.Scala._

/**
 * Assert the symbols emitted by ScalaMtags is a subset of semanticdb-scalac.
 *
 * It turns out ScalaMtags is actually more correct semanticdb-scalac when
 * it comes to trickier cases like implicit conversions and pattern matching.
 */
class MtagsSuite extends DirectoryExpectSuite("mtags") {

  def hasSemanticdbBug(file: InputFile): Boolean = {
    // don't assert fidelity where semanticdb-scalac has known bugs and mtags is correct.
    List(
      "ImplicitClasses",
      "PatternMatching",
      "ImplicitConversions",
      "MacroAnnotation"
    ).exists { name => file.file.toNIO.endsWith(s"$name.scala") }
  }

  def testCases(): List[ExpectTestCase] = {
    input.allFiles.map { file =>
      ExpectTestCase(
        file,
        { () =>
          val input = file.input
          val mtags = Mtags.index(input)
          val obtained = Semanticdbs.printTextDocument(mtags)
          val unknownSymbols = mtags.occurrences.collect {
            case occ if symtab.info(occ.symbol).isEmpty =>
              val pos = input.toPosition(occ)
              pos.formatMessage("error", s"unknown symbol: ${occ.symbol}")
          }
          if (unknownSymbols.nonEmpty) {
            fail(unknownSymbols.mkString("\n"))
          }
          if (file.isScala && !hasSemanticdbBug(file)) {
            // assert mtags produces same results as semanticdb-scalac
            val semanticdb = classpath.textDocument(file.file).get
            val globalDefinitions = semanticdb.occurrences.filter { occ =>
              occ.role.isDefinition &&
              occ.symbol.isGlobal
            }
            val semanticdbExpected = Semanticdbs.printTextDocument(
              semanticdb.withOccurrences(globalDefinitions)
            )
            Assertions.assertNoDiff(
              obtained,
              semanticdbExpected,
              "mtags == obtained, semanticdb-scalac == expected"
            )
          }
          obtained
        }
      )
    }
  }
}
