package tests

import scala.meta.Dialect
import scala.meta.dialects
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
abstract class MtagsSuite(
    inputProperties: => InputProperties,
    directory: String,
    dialect: Dialect,
    exclude: InputFile => Boolean,
    ignoreUnknownSymbols: Boolean = false
) extends DirectoryExpectSuite(directory) {

  override lazy val input: InputProperties = inputProperties

  def testCases(): List[ExpectTestCase] = {
    input.allFiles.map { file =>
      ExpectTestCase(
        file,
        { () =>
          val input = file.input
          val mtags = Mtags.index(input, dialect)
          val obtained = Semanticdbs.printTextDocument(mtags)
          val unknownSymbols = mtags.occurrences.collect {
            case occ if symtab.info(occ.symbol).isEmpty =>
              val pos = input.toPosition(occ)
              pos.formatMessage("error", s"unknown symbol: ${occ.symbol}")
          }
          if (!ignoreUnknownSymbols && unknownSymbols.nonEmpty) {
            fail(unknownSymbols.mkString("\n"))
          }
          if (file.isScala && !exclude(file)) {
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

class MtagsScala2Suite
    extends MtagsSuite(
      InputProperties.scala2(),
      "mtags",
      dialects.Scala213,
      (file: InputFile) => {
        // don't assert fidelity where semanticdb-scalac has known bugs and mtags is correct.
        List(
          "ImplicitClasses",
          "PatternMatching",
          "ImplicitConversions",
          "MacroAnnotation"
        ).exists { name => file.file.toNIO.endsWith(s"$name.scala") }
      }
    )
class MtagsScala3Suite
    extends MtagsSuite(
      InputProperties.scala3(),
      "mtags-scala3",
      dialects.Scala3,
      _ => true,
      // Do not assert unknown symbols and compare to semanticdb,
      // There're still lot to improve in mtags for Scala3
      ignoreUnknownSymbols = true
    )
