package tests.codeactions

import scala.meta.internal.metals.codeactions.ImplementAbstractMembers

class ImplementAbstractMembersLspSuite
    extends BaseCodeActionLspSuite("implementAbstractMembers") {

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
    s"""|${ImplementAbstractMembers.title}
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
    s"""|${ImplementAbstractMembers.title}
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
    s"""|${ImplementAbstractMembers.title}
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

  // Test ScalacDiagnostic can capture the multiple lines of diagnostic message.
  check(
    "object-creation-multiple-missing-members",
    """|package a
       |
       |object A {
       |  trait Foo {
       |    def foo(x: Int): Int
       |    def bar(x: Int): Int
       |  }
       |  new <<Foo>> {}
       |}
       |""".stripMargin,
    s"""|${ImplementAbstractMembers.title}
        |""".stripMargin,
    """|package a
       |
       |object A {
       |  trait Foo {
       |    def foo(x: Int): Int
       |    def bar(x: Int): Int
       |  }
       |  new Foo {
       |    override def foo(x: Int): Int = ???
       |    override def bar(x: Int): Int = ???
       |  }
       |}
       |""".stripMargin
  )

  check(
    "object-creation-iterator",
    """|package a
       |
       |object A {
       |  new <<Iterator>>[Int] {}
       |}
       |""".stripMargin,
    s"""|${ImplementAbstractMembers.title}
        |""".stripMargin,
    """|package a
       |
       |object A {
       |  new Iterator[Int] {
       |    override def hasNext: Boolean = ???
       |    override def next(): Int = ???
       |  }
       |}
       |""".stripMargin
  )

  check(
    "no-braces",
    """|package a
       |
       |object A {
       |  trait Foo {
       |    def foo(x: Int): Int
       |  }
       |  object <<Bar>> extends Foo
       |}
       |""".stripMargin,
    s"""|${ImplementAbstractMembers.title}
        |""".stripMargin,
    """|package a
       |
       |object A {
       |  trait Foo {
       |    def foo(x: Int): Int
       |  }
       |  object Bar extends Foo {
       |    override def foo(x: Int): Int = ???
       |  }
       |}
       |""".stripMargin
  )
}
