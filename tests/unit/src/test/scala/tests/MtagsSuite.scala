package tests

import scala.collection.mutable

import scala.meta.Dialect
import scala.meta.dialects
import scala.meta.internal.inputs._
import scala.meta.internal.mtags.Mtags
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.semanticdb.Scala._

import munit.IgnoreSuite

/**
 * Assert the symbols emitted by ScalaMtags is a subset of semanticdb-scalac.
 *
 * It turns out ScalaMtags is actually more correct semanticdb-scalac when
 * it comes to trickier cases like implicit conversions and pattern matching.
 */
abstract class MtagsSuite(
    loadInputProperties: () => InputProperties,
    directory: String,
    dialect: Dialect,
    exclude: InputFile => Boolean,
    ignoreUnknownSymbols: Boolean = false,
    documentedUnknownSymbols: Set[String] = Set.empty,
) extends DirectoryExpectSuite(directory) {

  private val discoveredUnknownSymbols = mutable.Set.empty[String]
  override lazy val input: InputProperties = loadInputProperties()

  override def afterAll(): Unit = {
    val diff = documentedUnknownSymbols -- discoveredUnknownSymbols
    assert(
      clue(diff).isEmpty,
      s"to fix this problem, make sure to list exactly the symbols that are unknown",
    )
  }

  def testCases(): List[ExpectTestCase] = {
    input.allFiles.map { file =>
      ExpectTestCase(
        file,
        { () =>
          val fileSymtab = symtab(file.file)
          val input = file.input
          val mtags = Mtags.index(file.file, dialect)
          val obtained = Semanticdbs.printTextDocument(mtags)
          val allUnknownSymbols = mtags.occurrences
            .collect {
              case occ
                  if fileSymtab.info(occ.symbol).isEmpty &&
                    !occ.symbol.endsWith("/") =>
                val pos = input.toPosition(occ)
                pos.formatMessage("error", s"unknown symbol: ${occ.symbol}")
            }
          val unknownSymbols = allUnknownSymbols.filterNot(msg => {
            documentedUnknownSymbols.exists { sym =>
              val result = msg.contains(sym)
              if (result) {
                discoveredUnknownSymbols += sym
              }
              result
            }
          })

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
              "mtags == obtained, semanticdb-scalac == expected",
            )
          }
          obtained
        },
      )
    }
  }
}

class MtagsScala2Suite
    extends MtagsSuite(
      () => InputProperties.scala2(),
      "mtags",
      dialects.Scala213,
      (file: InputFile) => {
        // don't assert fidelity where semanticdb-scalac has known bugs and mtags is correct.
        List(
          "ImplicitClasses",
          "PatternMatching",
          "ImplicitConversions",
          "MacroAnnotation",
        ).exists { name => file.file.toNIO.endsWith(s"$name.scala") }
      },
      documentedUnknownSymbols = Set(
        "example/JavaLocals#Point#x().", "example/JavaLocals#Point#y().",
        "example/JavaExtends#MyRecord#name().", "example/JavaRecord#a().",
        "example/JavaRecord#b().",
      ),
    )

// Ignored because it's failing to find SemanticDB files for Java sources. It
// seems like this test case assumes we run `++3.3.4 input/compile` but I can't
// get that working either.
@IgnoreSuite
class MtagsScala3Suite
    extends MtagsSuite(
      () => InputProperties.scala3(),
      "mtags-scala3",
      dialects.Scala3,
      _ => true,
      // Do not assert unknown symbols and compare to semanticdb,
      // There're still lot to improve in mtags for Scala3
      ignoreUnknownSymbols = true,
    )
