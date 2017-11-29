package tests.mtags

import scala.meta.languageserver.mtags.Mtags
import org.langmeta.internal.semanticdb.schema.Database
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
      val sdb = Database(Mtags.index(filename, original) :: Nil)
      val obtained = sdb.toDb(None).documents.head.syntax
//      println(obtained)
      assertNoDiff(obtained, expected)
    }
  }
}
