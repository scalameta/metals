package scala.meta.languageserver.ctags

import scala.meta.testkit.DiffAssertions
import org.scalatest.FunSuite

class BaseCtagsTest extends FunSuite with DiffAssertions {
  def check(filename: String, original: String, expected: String): Unit = {
    test(filename) {
      val obtained = Ctags.index(filename, original)
      println(obtained)
      assertNoDiff(obtained.syntax, expected)
    }
  }
}
