package tests.codeactions

import scala.meta.internal.metals.codeactions.ConvertToNamedArguments

import org.eclipse.lsp4j.CodeAction

class ConvertToNamedArgumentsLspSuite
    extends BaseCodeActionLspSuite(
      "convertToNamedArguments"
    ) {

  val filterAction: CodeAction => Boolean = { act: CodeAction =>
    ConvertToNamedArguments.title(".*").r matches act.getTitle()
  }

  check(
    "basic",
    """|object Something {
       |  case class Foo(param1: Int, param2: Int, param3: Int)
       |  Foo<<(>>1, 2, param3 = 3)
       |}""".stripMargin,
    s"${ConvertToNamedArguments.title("Foo(...)")}",
    """|object Something {
       |  case class Foo(param1: Int, param2: Int, param3: Int)
       |  Foo(param1 = 1, param2 = 2, param3 = 3)
       |}""".stripMargin,
    filterAction = filterAction,
  )

  check(
    "named-arg-first-position",
    """|object Something {
       |  case class Foo(param1: Int, param2: Int, param3: Int)
       |  Foo<<(>>param1 = 1, 2, 3)
       |}""".stripMargin,
    s"${ConvertToNamedArguments.title("Foo(...)")}",
    """|object Something {
       |  case class Foo(param1: Int, param2: Int, param3: Int)
       |  Foo(param1 = 1, param2 = 2, param3 = 3)
       |}""".stripMargin,
    filterAction = filterAction,
  )

  check(
    "def",
    """|object Something {
       |  def foo(param1: Int, param2: Int, param3: Int) = None
       |  foo<<(>>1, 2, param3 = 3)
       |}""".stripMargin,
    s"${ConvertToNamedArguments.title("foo(...)")}",
    """|object Something {
       |  def foo(param1: Int, param2: Int, param3: Int) = None
       |  foo(param1 = 1, param2 = 2, param3 = 3)
       |}""".stripMargin,
    filterAction = filterAction,
  )

  check(
    "long-chain",
    """|object Something {
       |  List(1, 2, 3).map(a => a + 1).filter(_ == 2).slice<<(>>0, 1)
       |}""".stripMargin,
    s"${ConvertToNamedArguments.title("slice(...)")}",
    """|object Something {
       |  List(1, 2, 3).map(a => a + 1).filter(_ == 2).slice(from = 0, until = 1)
       |}
       |""".stripMargin,
    filterAction = filterAction,
  )

  check(
    "multiple-arg-lists",
    """|object Something {
       |  def foo(param1: Int, param2: Int, param3: Int)(param4: Int) = None
       |  foo<<(>>1, 2, param3 = 3)(4)
       |}""".stripMargin,
    s"${ConvertToNamedArguments.title("foo(...)")}",
    """|object Something {
       |  def foo(param1: Int, param2: Int, param3: Int)(param4: Int) = None
       |  foo(param1 = 1, param2 = 2, param3 = 3)(4)
       |}""".stripMargin,
    filterAction = filterAction,
  )

  check(
    "new-apply",
    """|object Something {
       |  class Foo(param1: Int, param2: Int)
       |  val a = new Foo<<(>>1, param2 = 2)
       |}""".stripMargin,
    s"${ConvertToNamedArguments.title("Foo(...)")}",
    """|object Something {
       |  class Foo(param1: Int, param2: Int)
       |  val a = new Foo(param1 = 1, param2 = 2)
       |}""".stripMargin,
  )

  check(
    "new-apply-multiple",
    """|object Something {
       |  class Foo(param1: Int, param2: Int)(param3: Int)
       |  val a = new Foo(1<<,>> param2 = 2)(3)
       |}""".stripMargin,
    s"${ConvertToNamedArguments.title("Foo(...)")}",
    """|object Something {
       |  class Foo(param1: Int, param2: Int)(param3: Int)
       |  val a = new Foo(param1 = 1, param2 = 2)(param3 = 3)
       |}""".stripMargin,
  )
  check(
    "new-apply-multiple-type",
    """|object Something {
       |  class Foo[T](param1: Int, param2: Int)(param3: T)
       |  val a = new Foo[Int](1, param2 = 2)<<(>>3)
       |}""".stripMargin,
    s"${ConvertToNamedArguments.title("Foo[Int](...)")}",
    """|object Something {
       |  class Foo[T](param1: Int, param2: Int)(param3: T)
       |  val a = new Foo[Int](param1 = 1, param2 = 2)(param3 = 3)
       |}""".stripMargin,
  )

  check(
    "multiple-arg-lists-start-of-2nd",
    """|object Something {
       |  def foo(param1: Int, param2: Int, param3: Int)(param4: Int) = None
       |  foo(1, 2, param3 = 3)<<(>>4)
       |}""".stripMargin,
    s"${ConvertToNamedArguments.title("foo(...)")}",
    """|object Something {
       |  def foo(param1: Int, param2: Int, param3: Int)(param4: Int) = None
       |  foo(param1 = 1, param2 = 2, param3 = 3)(4)
       |}""".stripMargin,
    filterAction = filterAction,
  )

  check(
    "multiple-arg-lists-only-2nd",
    """|object Something {
       |  def foo(param1: Int, param2: Int, param3: Int)(param4: Int) = None
       |  foo(1, 2, param3 = 3)(<<4>>)
       |}""".stripMargin,
    s"${ConvertToNamedArguments.title("foo()(...)")}",
    """|object Something {
       |  def foo(param1: Int, param2: Int, param3: Int)(param4: Int) = None
       |  foo(1, 2, param3 = 3)(param4 = 4)
       |}""".stripMargin,
    filterAction = filterAction,
  )

  check(
    "multiple-arg-lists-only-3rd",
    """|object Something {
       |  def foo(param1: Int, param2: Int, param3: Int)(param4: Int)(param5: Int) = None
       |  foo(1, 2, param3 = 3)(4)(<<5>>)
       |}""".stripMargin,
    s"${ConvertToNamedArguments.title("foo()()(...)")}",
    """|object Something {
       |  def foo(param1: Int, param2: Int, param3: Int)(param4: Int)(param5: Int) = None
       |  foo(1, 2, param3 = 3)(4)(param5 = 5)
       |}""".stripMargin,
    filterAction = filterAction,
  )

  check(
    "implicit-param",
    """|object Something {
       |  def foo(param1: Int, param2: Int)(implicit param3: Int) = None
       |  implicit val x = 3
       |  foo(1, <<2>>)
       |}""".stripMargin,
    s"${ConvertToNamedArguments.title("foo(...)")}",
    """|object Something {
       |  def foo(param1: Int, param2: Int)(implicit param3: Int) = None
       |  implicit val x = 3
       |  foo(param1 = 1, param2 = 2)
       |}""".stripMargin,
    filterAction = filterAction,
  )

  check(
    "implicit-passed-explicitly",
    """|object Something {
       |  def foo(param1: Int, param2: Int)(implicit param3: Int) = None
       |  foo(1, 2)(<<3>>)
       |}""".stripMargin,
    s"${ConvertToNamedArguments.title("foo()(...)")}",
    """|object Something {
       |  def foo(param1: Int, param2: Int)(implicit param3: Int) = None
       |  foo(1, 2)(param3 = 3)
       |}""".stripMargin,
    filterAction = filterAction,
  )

  check(
    "named-arg-in-middle",
    """|object Something {
       |  case class Foo(param1: Int, param2: Int, param3: Int)
       |  Foo<<(>>1, param2 = 2, 3)
       |}""".stripMargin,
    s"${ConvertToNamedArguments.title("Foo(...)")}",
    """|object Something {
       |  case class Foo(param1: Int, param2: Int, param3: Int)
       |  Foo(param1 = 1, param2 = 2, param3 = 3)
       |}""".stripMargin,
    filterAction = filterAction,
  )

  checkNoAction(
    "no-unnamed-args",
    """|object Something {
       |  case class Foo(param1: Int, param2: Int, param3: Int)
       |  Foo<<(>>param1 = 1, param2 = <<>>2, param3 = 3)
       |}""".stripMargin,
    filterAction = filterAction,
  )

  checkNoAction(
    "dont-convert-block",
    """|object Something {
       |  def f(x: Seq[Int]) = x.map <<{>> _.toLong }
       |}""".stripMargin,
    filterAction = filterAction,
  )

  check(
    "go-to-parent-apply",
    """|object Something {
       |  case class Foo(param1: Int, param2: Int, param3: String)
       |  Foo(1, 2, 3.t<<>>oString())
       |}""".stripMargin,
    s"${ConvertToNamedArguments.title("Foo(...)")}",
    """|object Something {
       |  case class Foo(param1: Int, param2: Int, param3: String)
       |  Foo(param1 = 1, param2 = 2, param3 = 3.toString())
       |}""".stripMargin,
    filterAction = filterAction,
  )

  checkNoAction(
    "cursor-outside-func",
    """|object Something {
       |  import scala.concurrent.Future
       |  F<<u>>ture.successful(1)
       |}""".stripMargin,
    filterAction = filterAction,
  )

}
