package tests

import scala.meta.internal.jsemanticdb.Semanticdb
import scala.meta.internal.mtags.SemanticdbPrinter
import scala.meta.internal.mtags.proto.ProtoMtagsV1
import scala.meta.internal.mtags.proto.ProtoMtagsV2

/**
 * Expect test suite for protobuf mtags indexer (V1).
 *
 * This suite verifies that ProtoMtagsV1 produces correct SemanticDB
 * output for protobuf files. The expected output files are stored in
 * tests/unit/src/test/resources/mtags-protobuf/.
 *
 * To update expect files after making changes to the protobuf indexer:
 * {{{
 * coursier launch sbt -- --client "unit/Test/runMain tests.SaveExpect ProtobufMtagsExpectSuite"
 * }}}
 */
class ProtobufMtagsExpectSuite extends DirectoryExpectSuite("mtags-protobuf") {

  override lazy val input: InputProperties = InputProperties.scala2()

  def testCases(): List[ExpectTestCase] = for {
    file <- input.allFiles
    if file.file.toNIO.toString.endsWith(".proto")
  } yield {
    ExpectTestCase(
      file,
      () => {
        val indexer = new ProtoMtagsV1(
          file.input,
          includeGeneratedSymbols = true,
          includeFuzzyReferences = true,
        )

        val sdoc = indexer.index()
        val mdoc = Semanticdb.TextDocument.parseFrom(sdoc.toByteArray)
        SemanticdbPrinter.printDocument(mdoc, includeInfo = true)
      },
    )
  }
}

/**
 * Expect test suite for protobuf mtags indexer (V2).
 *
 * This suite verifies that ProtoMtagsV2 produces correct SemanticDB
 * output for protobuf files, including gRPC stub reference occurrences
 * needed for find-implementations bloom filter queries.
 *
 * Key features verified:
 * - gRPC stub class references (e.g., `UserServiceImplBase:`) for bloom filter
 * - Java method symbols (e.g., `getUser()`) for stub method navigation
 *
 * Expected output files are stored in tests/unit/src/test/resources/mtags-protobuf-v2/.
 *
 * To update expect files:
 * {{{
 * coursier launch sbt -- --client "unit/Test/runMain tests.SaveExpect ProtobufMtagsV2ExpectSuite"
 * }}}
 */
class ProtobufMtagsV2ExpectSuite
    extends DirectoryExpectSuite("mtags-protobuf-v2") {

  override lazy val input: InputProperties = InputProperties.scala2()

  def testCases(): List[ExpectTestCase] = for {
    file <- input.allFiles
    if file.file.toNIO.toString.endsWith(".proto")
  } yield {
    ExpectTestCase(
      file,
      () => {
        val indexer = new ProtoMtagsV2(
          file.input,
          includeMembers = true,
          includeGeneratedSymbols = true,
        )

        val sdoc = indexer.index()
        val mdoc = Semanticdb.TextDocument.parseFrom(sdoc.toByteArray)
        SemanticdbPrinter.printDocument(mdoc, includeInfo = true)
      },
    )
  }
}
