package tests

import scala.meta.internal.jsemanticdb.Semanticdb
import scala.meta.internal.metals.EmptyReportContext
import scala.meta.internal.mtags.KotlinMtags
import scala.meta.internal.mtags.SemanticdbPrinter

/**
 * Expect tests for the Kotlin indexer. Inputs are in
 * tests/input/src/main/kotlin, outputs in
 * tests/unit/src/test/resources/mtags-kotlin.
 *
 * To update the expect files:
 * {{{
 * coursier launch sbt -- --client "unit/Test/runMain tests.SaveExpect mtags-kotlin"
 * }}}
 */
class KotlinMtagsExpectSuite extends DirectoryExpectSuite("mtags-kotlin") {

  override lazy val input: InputProperties = InputProperties.scala2()

  def testCases(): List[ExpectTestCase] = for {
    file <- input.allFiles
    if file.file.toNIO.toString.endsWith(".kt")
  } yield {
    ExpectTestCase(
      file,
      () => {
        val sdoc = new KotlinMtags(file.input, includeMembers = true)(
          EmptyReportContext
        ).index()
        val mdoc = Semanticdb.TextDocument.parseFrom(sdoc.toByteArray)
        SemanticdbPrinter.printDocument(mdoc, includeInfo = true)
      },
    )
  }
}
