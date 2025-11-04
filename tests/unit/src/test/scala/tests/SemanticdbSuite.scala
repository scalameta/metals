package tests

import scala.util.Properties

import scala.meta.internal.jsemanticdb.Semanticdb
import scala.meta.internal.mtags.SemanticdbPrinter

/**
 * Baseline test suite that documents the unprocessed output of semanticdb-scalac
 *
 * This test suite does not test any metals functionality, it is only to see what
 * semanticdb-scalac procudes.
 */
abstract class SemanticdbSuite(
    inputProperties: => InputProperties,
    directory: String,
) extends DirectoryExpectSuite(s"$directory") {

  override lazy val input: InputProperties = inputProperties

  override def testCases(): List[ExpectTestCase] = {
    def isEnabled(f: InputFile): Boolean = {
      if (
        Properties.isWin &&
        f.file.toNIO.getFileName.endsWith("MacroAnnotation.scala")
      ) {
        // Produces inconsistent positions on Windows vs. Unix.
        false
      } else {
        true
      }
    }
    for {
      file <- input.allFiles
      if isEnabled(file)
      if file.isScalaOrJava
    } yield ExpectTestCase(
      file,
      { () =>
        val textDocument = classpath.textDocument(file.file).get
        val jdoc = Semanticdb.TextDocument.parseFrom(textDocument.toByteArray)
        val obtained = SemanticdbPrinter.printDocument(jdoc)
        obtained
      },
    )
  }
}

class SemanticdbScala2Suite
    extends SemanticdbSuite(InputProperties.scala2(), "semanticdb")

/**
 * There is a number of issues observed in Scala 3 that can be seen in the tests:
 * - extension methods https://github.com/lampepfl/dotty/issues/11690
 * - enums https://github.com/lampepfl/dotty/issues/11689
 * - anonymous givens https://github.com/lampepfl/dotty/issues/11692
 * - topelevel symbols https://github.com/lampepfl/dotty/issues/11693
 */
@munit.IgnoreSuite
class SemanticdbScala3Suite
    extends SemanticdbSuite(InputProperties.scala3(), "semanticdb-scala3")
