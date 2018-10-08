package tests

import scala.meta.internal.mtags.Semanticdbs

/**
 * Baseline test suite that documents the unprocessed output of semanticdb-scalac
 *
 * This test suite does not test any metals functionality, it is only to see what
 * semanticdb-scalac procudes.
 */
object SemanticdbSuite extends DirectoryExpectSuite("semanticdb") {
  override def testCases(): List[ExpectTestCase] = {
    input.scalaFiles.map { file =>
      ExpectTestCase(
        file, { () =>
          val textDocument = classpath.textDocument(file.file).get
          val obtained = Semanticdbs.printTextDocument(textDocument)
          obtained
        }
      )
    }
  }
}
