package tests

import scala.meta.internal.metals.Urls
import scala.meta.internal.metals.ServerCommands

object OpenBrowserSuite extends BaseSuite {
  val scalaLang = "https://www.scala-lang.org/"
  test("open-url") {
    val command = ServerCommands.OpenBrowser(scalaLang)
    val ServerCommands.OpenBrowser(url) = command
    assertNoDiff(url, scalaLang)
  }
  // Not sure we can easily test this automatically, but we can at least manually run this main function
  // to see if it works for our version.
  def main(args: Array[String]): Unit = {
    Urls.openBrowser(scalaLang)
  }
}
