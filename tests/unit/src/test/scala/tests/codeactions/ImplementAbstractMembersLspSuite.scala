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
      _ <- server.didChange(path)(_ => """|package a
                                          |
                                          |object Impl extends Service
                                          |""".stripMargin)
      _ <- server.didSave(path)
      codeActions <-
        server
          .assertCodeAction(
            path,
            """|package a
               |
               |<<object Impl extends Service>>
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

  // https://github.com/scalameta/metals/issues/2554
  // A representable Java raw type can be implemented, so the quick fix is
  // offered and the applied stub compiles.
  checkEdit(
    "java-raw-type-representable-offered",
    s"""|/metals.json
        |{"a": {"scalaVersion": "$scalaVersion"}}
        |/a/src/main/java/example/Box.java
        |package example;
        |public interface Box<T> {}
        |/a/src/main/java/example/Reasonable.java
        |package example;
        |public interface Reasonable {
        |  public void box(Box b);
        |}
        |/a/src/main/scala/example/Reasonably.scala
        |package example
        |class <<Reasonably>> extends Reasonable {}
        |""".stripMargin,
    s"""|${ImplementAbstractMembers.title}
        |""".stripMargin,
    expectedCode = """|package example
                      |class Reasonably extends Reasonable {
                      |
                      |  override def box(b: Box[_]): Unit = ???
                      |
                      |}
                      |""".stripMargin,
    filterAction = _.getTitle() == ImplementAbstractMembers.title,
  )

  // An F-bounded Java raw type has no compiling override, so the class can't be
  // made concrete and the quick fix must NOT be offered (it would leave the
  // class abstract).
  checkEdit(
    "java-raw-type-recursive-not-offered",
    s"""|/metals.json
        |{"a": {"scalaVersion": "$scalaVersion"}}
        |/a/src/main/java/example/Recursive.java
        |package example;
        |public interface Recursive<T extends Recursive> {}
        |/a/src/main/java/example/IllConceived.java
        |package example;
        |public interface IllConceived {
        |  public void impossible(Recursive r);
        |}
        |/a/src/main/scala/example/Naivety.scala
        |package example
        |class <<Naivety>> extends IllConceived {}
        |""".stripMargin,
    expectedActions = "",
    expectedCode = "",
    expectNoDiagnostics = false,
    filterAction = _.getTitle() == ImplementAbstractMembers.title,
    applyCodeAction = false,
  )
}
