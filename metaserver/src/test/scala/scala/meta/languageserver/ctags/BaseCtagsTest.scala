package scala.meta.languageserver.ctags

import org.langmeta.internal.semanticdb.schema.Database
import tests.MegaSuite

class BaseCtagsTest extends MegaSuite {
  def checkIgnore(
      filename: String,
      original: String,
      expected: String
  ): Unit = {
    ignore(filename) {}
  }
  def check(filename: String, original: String, expected: String): Unit = {
    test(filename) {
      val obtained = Database(Ctags.index(filename, original) :: Nil)
//      println(obtained)
      assertNoDiff(obtained.toDb(None).documents.head.syntax, expected)
    }
  }
}
