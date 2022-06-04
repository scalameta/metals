package tests.codeactions

import scala.meta.internal.metals.codeactions.ConvertToNamedArguments

class ConvertToNamedArgumentsSuite extends BaseCodeActionLspSuite("convertToNamedArguments") {

  check(
    "convert-to-named-args-basic",
    """|object Something {
       |  case class Foo(param1: Int, param2: Int, param3: Int)
       |  Foo<<(>>1, 2, param3 = 3)
       |}""".stripMargin,
    s"""|${ConvertToNamedArguments.title}
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
       |}""".stripMargin,
  )
}
