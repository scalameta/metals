package tests.codeactions

import scala.meta.internal.metals.codeactions.ConvertToNamedArguments
import scala.meta.internal.metals.codeactions.ExtractValueCodeAction

class ConvertToNamedArgumentsSuite
    extends BaseCodeActionLspSuite("convertToNamedArguments") {

  check(
    "convert-to-named-args-basic",
    """|object Something {
       |  case class Foo(param1: Int, param2: Int, param3: Int)
       |  Foo<<(>>1, 2, param3 = 3)
       |}""".stripMargin,
    s"""|${ConvertToNamedArguments.title("Foo")}
        |""".stripMargin,
    """|object Something {
       |  case class Foo(param1: Int, param2: Int, param3: Int)
       |  Foo(param1 = 1, param2 = 2, param3 = 3)
       |}""".stripMargin,
    selectedActionIndex = 0
  )

  checkNoAction(
    "convert-to-named-args-no-unnamed-args",
    """|object Something {
       |  case class Foo(param1: Int, param2: Int, param3: Int)
       |  Foo<<(>>param1 = 1, param2 = <<>>2, param3 = 3)
       |}""".stripMargin
  )

  checkActionMissing(
    "convert-to-named-args-block",
    """|object Something {
       |  def f(x: Seq[Int]) = x.map <<{>> _.toLong }
       |}""".stripMargin,
    ConvertToNamedArguments.title("map")
  )

  check(
    "convert-to-named-go-to-parent-apply",
    """|object Something {
       |  case class Foo(param1: Int, param2: Int, param3: String)
       |  Foo(1, 2, 3.t<<>>oString())
       |}""".stripMargin,
    s"""|${ExtractValueCodeAction.title}
        |${ConvertToNamedArguments.title("Foo")}
        |""".stripMargin,
    """|object Something {
       |  case class Foo(param1: Int, param2: Int, param3: String)
       |  Foo(param1 = 1, param2 = 2, param3 = 3.toString())
       |}""".stripMargin,
    selectedActionIndex = 1
  )
}
