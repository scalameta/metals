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
       |
       |    override def foo(x: Int): Int = ???
       |
       |    override def bar(x: String): String = ???
       |
       |  }
       |}
       |""".stripMargin,
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
       |
       |    override def foo(x: Int): Int = ???
       |
       |    override def bar(x: String): String = ???
       |
       |  }
       |}
       |""".stripMargin,
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
       |
       |    override def foo(x: Int): Int = ???
       |
       |  }
       |}
       |""".stripMargin,
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
       |
       |    override def foo(x: Int): Int = ???
       |
       |    override def bar(x: Int): Int = ???
       |
       |  }
       |}
       |""".stripMargin,
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
       |
       |    override def hasNext: Boolean = ???
       |
       |    override def next(): Int = ???
       |
       |  }
       |}
       |""".stripMargin,
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
       |
       |    override def foo(x: Int): Int = ???
       |
       |  }
       |}
       |""".stripMargin,
  )

  check(
    "dots-in-name",
    """|package a
       |
       |object Main {
       |  object Outer {
       |    object x {
       |      trait Nested {
       |        def foo: String
       |      }
       |    }
       |  }
       |  new <<Outer.x.Nested>> {}
       |}
       |""".stripMargin,
    s"""|${ImplementAbstractMembers.title}
        |""".stripMargin,
    """|package a
       |
       |object Main {
       |  object Outer {
       |    object x {
       |      trait Nested {
       |        def foo: String
       |      }
       |    }
       |  }
       |  new Outer.x.Nested {
       |
       |    override def foo: String = ???
       |
       |  }
       |}
       |""".stripMargin,
  )

  check(
    "java",
    """|package example
       |
       |import java.io.Externalizable
       |
       |object <<A>> extends Externalizable {
       |}
       |""".stripMargin,
    s"""|${ImplementAbstractMembers.title}
        |""".stripMargin,
    """|package example
       |
       |import java.io.Externalizable
       |import java.io.ObjectOutput
       |import java.io.ObjectInput
       |
       |object A extends Externalizable {
       |
       |  override def writeExternal(out: ObjectOutput): Unit = ???
       |
       |  override def readExternal(in: ObjectInput): Unit = ???
       |
       |}
       |""".stripMargin,
  )

  test("string-type") {
    val path = "a/src/main/scala/a/Impl.scala"
    val fullInput =
      s"""|/metals.json
          |{ "a": {} }
          |/a/src/main/scala/a/Service.scala
          |package a
          |
          |trait Service {
          |  def markdown(mode: "mode"): String
          |}
          |
          |/$path
          |package a
          |
          |object Impl
          |""".stripMargin

    cleanWorkspace()
    for {
      _ <- initialize(fullInput)
      _ <- server.didOpen(path)
      _ <- server.didSave(path)(txt => """|package a
                                          |
                                          |object Impl extends Service
                                          |""".stripMargin)
      codeActions <-
        server
          .assertCodeAction(
            path,
            """|package a
               |
               |object Impl extends <<Service>>
               |""".stripMargin,
            s"""|${ImplementAbstractMembers.title}
                |""".stripMargin,
            Nil,
          )
          .recover { case _: Throwable =>
            Nil
          }
      _ <- client.applyCodeAction(0, codeActions, server)
      _ = assertNoDiff(
        server.bufferContents(path),
        """|package a
           |
           |object Impl extends Service {
           |
           |  override def markdown(mode: "mode"): String = ???
           |
           |}
           |""".stripMargin,
      )
    } yield ()
  }
}
