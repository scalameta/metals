package tests.mtags

import scala.meta.languageserver.mtags.Mtags
import scala.meta.internal.semanticdb3.TextDocuments
import org.langmeta.internal.semanticdb.XtensionSchemaTextDocuments
import tests.MegaSuite

class BaseMtagsTest extends MegaSuite {
  def checkIgnore(
      filename: String,
      original: String,
      expected: String
  ): Unit = {
    ignore(filename) {}
  }
  def check(filename: String, original: String, expected: String): Unit = {
    test(filename) {
      val docs = TextDocuments(Mtags.index(filename, original) :: Nil)
      val obtained = docs.toDb(None).documents.head.syntax
//      println(obtained)
      assertNoDiff(obtained, expected)
    }
  }
}
