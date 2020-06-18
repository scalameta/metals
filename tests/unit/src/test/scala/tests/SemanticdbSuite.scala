package tests

import scala.util.Properties

import scala.meta.internal.mtags.Semanticdbs

/**
 * Baseline test suite that documents the unprocessed output of semanticdb-scalac
 *
 * This test suite does not test any metals functionality, it is only to see what
 * semanticdb-scalac procudes.
 */
class SemanticdbSuite extends DirectoryExpectSuite("semanticdb") {
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
    input.scalaFiles.filter(isEnabled).map { file =>
      ExpectTestCase(
        file,
        { () =>
          val textDocument = classpath.textDocument(file.file).get
          val obtained = Semanticdbs.printTextDocument(textDocument)
          obtained
        }
      )
    }
  }
}
