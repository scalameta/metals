package tests.codeactions

import scala.meta.internal.metals.codeactions.ConvertToNamedArguments

class ConvertToNamedArgumentsSuite
    extends BaseCodeActionLspSuite("convertToNamedArguments", filterAction = ConvertToNamedArguments.title(".*").r matches _.getTitle() ) {


  check(
    "basic",
    """|object Something {
       |  case class Foo(param1: Int, param2: Int, param3: Int)
       |  Foo<<(>>1, 2, param3 = 3)
       |}""".stripMargin,
    s"${ConvertToNamedArguments.title("Foo")}",
    """|object Something {
       |  case class Foo(param1: Int, param2: Int, param3: Int)
       |  Foo(param1 = 1, param2 = 2, param3 = 3)
       |}""".stripMargin
  )

  check(
    "named-arg-in-middle",
    """|object Something {
       |  case class Foo(param1: Int, param2: Int, param3: Int)
       |  Foo<<(>>1, param2 = 2, 3)
       |}""".stripMargin,
    s"${ConvertToNamedArguments.title("Foo")}",
    """|object Something {
       |  case class Foo(param1: Int, param2: Int, param3: Int)
       |  Foo(param1 = 1, param2 = 2, param3 = 3)
       |}""".stripMargin
  )

  checkNoAction(
    "no-unnamed-args",
    """|object Something {
       |  case class Foo(param1: Int, param2: Int, param3: Int)
       |  Foo<<(>>param1 = 1, param2 = <<>>2, param3 = 3)
       |}""".stripMargin
  )

  checkNoAction(
    "dont-convert-block",
    """|object Something {
       |  def f(x: Seq[Int]) = x.map <<{>> _.toLong }
       |}""".stripMargin
  )

  check(
    "go-to-parent-apply",
    """|object Something {
       |  case class Foo(param1: Int, param2: Int, param3: String)
       |  Foo(1, 2, 3.t<<>>oString())
       |}""".stripMargin,
      s"${ConvertToNamedArguments.title("Foo")}",
    """|object Something {
       |  case class Foo(param1: Int, param2: Int, param3: String)
       |  Foo(param1 = 1, param2 = 2, param3 = 3.toString())
       |}""".stripMargin
  )
}
