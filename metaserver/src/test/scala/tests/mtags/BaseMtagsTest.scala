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
      val obtained = Database(Mtags.index(filename, original) :: Nil)
//      println(obtained)
      assertNoDiff(obtained.toDb(None).documents.head.syntax, expected)
    }
  }
}
