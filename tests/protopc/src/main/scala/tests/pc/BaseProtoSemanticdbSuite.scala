package tests.pc

import java.nio.file.Paths

import scala.meta.internal.jsemanticdb.Semanticdb
import scala.meta.internal.mtags.SemanticdbPrinter

import munit.Location
import munit.TestOptions

class BaseProtoSemanticdbSuite extends BaseProtoPCSuite {

  def check(
      testOpt: TestOptions,
      original: String,
      expected: String,
  )(implicit loc: Location): Unit = {
    test(testOpt) {
      val uri = Paths.get("test.proto").toUri()
      val bytes =
        presentationCompiler.semanticdbTextDocument(uri, original).get()
      val doc = Semanticdb.TextDocument.parseFrom(bytes)
      val obtained = SemanticdbPrinter.printDocument(doc, includeInfo = true)

      assertNoDiff(
        obtained,
        expected,
      )
    }
  }
}
