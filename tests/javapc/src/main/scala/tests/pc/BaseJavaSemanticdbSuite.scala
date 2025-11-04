package tests.pc

import java.net.URI

import scala.meta.internal.jsemanticdb.Semanticdb
import scala.meta.internal.metals.CompilerVirtualFileParams
import scala.meta.internal.mtags.SemanticdbPrinter

import munit.Location
import munit.TestOptions

class BaseJavaSemanticdbSuite extends BaseJavaPCSuite {

  def textDocument(code: String, uri: URI): Semanticdb.TextDocument = {
    val pcParams = CompilerVirtualFileParams(uri, code)
    val bytes = presentationCompiler.semanticdbTextDocument(pcParams).get()
    Semanticdb.TextDocument.parseFrom(bytes)
  }

  def check(
      testOpt: TestOptions,
      original: String,
      expected: String,
      uri: Option[URI] = None,
      filename: String = "SemanticdbInput.java",
  )(implicit loc: Location): Unit = {
    test(testOpt) {
      val pkg = packageName(testOpt.name)
      val code = s"package $pkg;\n$original"
      val actualUri = uri.getOrElse(URI.create(s"file:///$filename"))
      val doc = textDocument(code, actualUri)
      val obtained = SemanticdbPrinter.printDocument(doc)

      assertNoDiff(
        obtained,
        expected,
      )
    }
  }

}
