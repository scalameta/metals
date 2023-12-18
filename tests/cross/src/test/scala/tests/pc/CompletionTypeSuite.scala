package tests.pc

import tests.BaseCompletionSuite

class CompletionTypeSuite extends BaseCompletionSuite {

  check(
    "type-bound",
    s"""|import java.nio.file.{FileSystem => FS}
        |
        |object O {
        |  class Foo[T] {
        |    def method[T <: FS](a: T) = ???
        |  }
        |  val foo = new Foo
        |  foo.met@@
        |}
        |""".stripMargin,
    s"""|method[T <: FS](a: T): Nothing
        |""".stripMargin,
    compat = Map(
      "2" ->
        """|method[T <: FileSystem](a: T): Nothing
           |""".stripMargin
    )
  )

}
