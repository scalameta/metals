package tests.pc

import java.net.URI

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.jsemanticdb.Semanticdb
import scala.meta.internal.metals.CompilerVirtualFileParams
import scala.meta.internal.mtags.SemanticdbPrinter
import scala.meta.pc.VirtualFileParams

import munit.Location
import munit.TestOptions

class BaseJavaSemanticdbSuite extends BaseJavaPCSuite {

  def textDocument(code: String, uri: URI): Semanticdb.TextDocument = {
    val pcParams = CompilerVirtualFileParams(uri, code)
    val bytes = presentationCompiler.semanticdbTextDocument(pcParams).get()
    Semanticdb.TextDocument.parseFrom(bytes)
  }

  def batchTextDocuments(
      params: List[VirtualFileParams]
  ): List[Semanticdb.TextDocument] = {
    val bytes =
      presentationCompiler
        .batchSemanticdbTextDocuments(params.asJava)
        .get()
    Semanticdb.TextDocuments
      .parseFrom(bytes)
      .getDocumentsList()
      .asScala
      .toList
  }

  def checkBatch(
      testOpt: TestOptions,
      original: Map[String, String],
      expected: String,
  )(implicit loc: Location): Unit = {
    test(testOpt) {
      val docs = batchTextDocuments(original.map { case (uri, code) =>
        CompilerVirtualFileParams(URI.create(s"file:///$uri"), code)
      }.toList)
      assertEquals(docs.size, original.size)
      val obtained =
        docs
          .flatMap(doc =>
            List(
              "==========",
              doc.getUri(),
              SemanticdbPrinter.printDocument(doc),
            )
          )
          .mkString("\n")
      assertNoDiff(obtained, expected)
    }
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
      val docs =
        batchTextDocuments(List(CompilerVirtualFileParams(actualUri, code)))
      assertEquals(docs.size, 1)
      assertEquals(
        docs.head,
        doc,
        "batchTextDocuments should return the same document as textDocument",
      )
      val obtained = SemanticdbPrinter.printDocument(doc)

      assertNoDiff(
        obtained,
        expected,
      )
    }
  }

}
