package tests.codeactions

import scala.meta.internal.metals.codeactions.ImplementAbstractMethods

class ImplementMethodsLspSuite
    extends BaseCodeActionLspSuite("implementMethods") {

  check(
    "classdef",
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |  }
       |  class <<Concrete>> extends Base {
       |  }
       |}
       |""".stripMargin,
    s"""|${ImplementAbstractMethods.title}
        |""".stripMargin,
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |  }
       |  class Concrete extends Base {
       |    override def foo(x: Int): Int = ???
       |    override def bar(x: String): String = ???
       |  }
       |}
       |""".stripMargin
  )

  check(
    "enclosed-range",
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |  }
       |  class Con<<cre>>te extends Base {
       |  }
       |}
       |""".stripMargin,
    s"""|${ImplementAbstractMethods.title}
        |""".stripMargin,
    """|package a
       |
       |object A {
       |  trait Base {
       |    def foo(x: Int): Int
       |    def bar(x: String): String
       |  }
       |  class Concrete extends Base {
       |    override def foo(x: Int): Int = ???
       |    override def bar(x: String): String = ???
       |  }
       |}
       |""".stripMargin
  )

  check(
    "object-creation",
    """|package a
       |
       |object A {
       |  trait Foo {
       |    def foo(x: Int): Int
       |  }
       |  new <<Foo>> {}
       |}
       |""".stripMargin,
    s"""|${ImplementAbstractMethods.title}
        |""".stripMargin,
    """|package a
       |
       |object A {
       |  trait Foo {
       |    def foo(x: Int): Int
       |  }
       |  new Foo {
       |    override def foo(x: Int): Int = ???
       |  }
       |}
       |""".stripMargin
  )

}
