package tests.mtags

import scala.meta.metals.mtags.Mtags
import tests.MegaSuite
import scala.meta.internal.semanticdb3.TextDocuments
import org.langmeta.internal.semanticdb._

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
      val sdb = TextDocuments(Mtags.index(filename, original) :: Nil)
      val obtained = sdb.toDb(None).documents.head.syntax
//      println(obtained)
      assertNoDiff(obtained, expected)
    }
  }
}
