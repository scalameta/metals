package tests.codeactions

import scala.meta.internal.metals.codeactions.CreateNewSymbol
import scala.meta.internal.metals.codeactions.ImportMissingGiven

class ImportMissingGivenLspSuite
    extends BaseCodeActionLspSuite("importMissingGiven") {

  check(
    "basic",
    """|trait X
       |trait Y
       |object test:
       |  def f(using x: X) = ???
       |  locally {
       |    object instances {
       |      given xFromY(using Y) as X = ???
       |    }
       |    <<f>> // error
       |  }
    """.stripMargin,
    s"""|${ImportMissingGiven.title("instances", "xFromY")}
        |""".stripMargin,
    """|trait X
       |trait Y
       |object test:
       |  def f(using x: X) = ???
       |  locally {
       |    object instances {
       |      given xFromY(using Y) as X = ???
       |    }
       |    <<f>> // error
       |  }
       |""".stripMargin
  )

}
