package tests

import scala.meta.internal.metals.Directories
import scala.meta.internal.metals.{BuildInfo => V}

class HoverLspSuite extends BaseLspSuite("hover") with TestHovers {

  test("basic".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
        """.stripMargin
      )
      _ <- server.assertHover(
        "a/src/main/scala/a/Main.scala",
        """
          |object Main {
          |  Option(1).he@@ad
          |}""".stripMargin,
        """|Int
           |override def head: Int""".stripMargin.hover
      )
    } yield ()
  }

  test("basic-rambo".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        s"""|/metals.json
            |{"a":{"scalaVersion" : ${V.scala212}}}
            |/Main.scala
            |object Main extends App {
            |  // @@
            |}
            |""".stripMargin,
        expectError = true
      )
      _ <- server.assertHover(
        "a/src/main/scala/a/Main.scala",
        """
          |object Main {
          |  Option(1).he@@ad
          |}""".stripMargin,
        """|Int
           |override def head: Int""".stripMargin.hover
      )
    } yield ()
  }

  test("docstrings".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/a/Def.scala
          |package a
          |object Def {
          |  /**
          |    * test
          |    */
          |  def foo(x: Int): Int = ???
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/Def.scala")(s => s) // index docs
      _ <- server.assertHover(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Def.fo@@o(1)
          |}""".stripMargin,
        """|**Expression type**:
           |```scala
           |Int
           |```
           |**Symbol signature**:
           |```scala
           |def foo(x: Int): Int
           |```
           |test""".stripMargin
      )
    } yield ()
  }

  test("update-docstrings".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/a/Def.scala
          |package a
          |object Def {
          |  /**
          |    * test
          |    */
          |  def foo(x: Int): Int = ???
          |}
        """.stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/Def.scala")(s => s)
      _ <- server.assertHover(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Def.fo@@o(1)
          |}""".stripMargin,
        """|**Expression type**:
           |```scala
           |Int
           |```
           |**Symbol signature**:
           |```scala
           |def foo(x: Int): Int
           |```
           |test""".stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/Def.scala")(s =>
        s.replace("test", "test2")
      )
      _ <- server.assertHover(
        "a/src/main/scala/a/Main.scala",
        """
          |package a
          |object Main {
          |  val res = Def.fo@@o(1)
          |}""".stripMargin,
        """|**Expression type**:
           |```scala
           |Int
           |```
           |**Symbol signature**:
           |```scala
           |def foo(x: Int): Int
           |```
           |test2""".stripMargin
      )
    } yield ()
  }

  test("dependencies".tag(FlakyWindows)) {
    for {
      _ <- initialize(
        """/metals.json
          |{"a":{}}
          |/a/src/main/scala/a/Main.scala
          |package a
          |object Main {
          |  println(42)
          |}
        """.stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      _ =
        server.workspaceDefinitions // triggers goto definition, creating Predef.scala
      _ <- server.assertHover(
        "scala/Predef.scala",
        """
          |object Main {
          |  Option(1).he@@ad
          |}""".stripMargin,
        """|Int
           |override def head: Int""".stripMargin.hover,
        root = workspace.resolve(Directories.readonly)
      )
    } yield ()
  }

}
