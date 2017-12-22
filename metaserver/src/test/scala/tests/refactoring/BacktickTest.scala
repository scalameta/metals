package tests.refactoring

import scala.meta.languageserver.refactoring.Backtick
import tests.MegaSuite

object BacktickTest extends MegaSuite {

  def checkOK(identifier: String, expected: String): Unit = {
    test(s"OK   $identifier") {
      Backtick.backtickWrap(identifier) match {
        case Right(obtained) =>
          assertNoDiff(obtained, expected)
        case Left(err) => fail(err)
      }
    }
  }

  def checkFail(identifier: String): Unit = {
    test(s"FAIL $identifier") {
      Backtick.backtickWrap(identifier) match {
        case Right(obtained) => fail(s"Expected error, obtained: $obtained")
        case Left(_) => // OK
      }
    }
  }

  checkFail("`")
  checkFail("`a ``")
  checkOK("a b", "`a b`")
  checkOK("++", "++")
  checkOK("foo_", "foo_")
  checkOK("foo_ a", "`foo_ a`")
  checkOK("a.b", "`a.b`")

}
