package scala.meta.languageserver.ctags

import scala.meta.testkit.DiffAssertions
import org.langmeta.internal.semanticdb.schema.Database
import org.scalatest.FunSuite

class BaseCtagsTest extends FunSuite with DiffAssertions {
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
        .toDb(None)
        .documents
        .head
      println(obtained)
      assertNoDiff(obtained.syntax, expected)
    }
  }
}
