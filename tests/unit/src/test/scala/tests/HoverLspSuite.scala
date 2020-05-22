package tests

import scala.meta.internal.metals.Directories

class HoverLspSuite extends BaseLspSuite("hover") with TestHovers {

  test("basic".tag(FlakyWindows)) {
    for {
      _ <- server.initialize(
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
        """override def head: Int""".hover
      )
    } yield ()
  }

  test("basic-rambo".tag(FlakyWindows)) {
    for {
      _ <- server.initialize(
        """|/a/src/main/scala/a/Main.scala
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
        """override def head: Int""".hover
      )
    } yield ()
  }

  test("docstrings".tag(FlakyWindows)) {
    for {
      _ <- server.initialize(
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
        """|```scala
           |def foo(x: Int): Int
           |```
           |test
           |""".stripMargin.hover
      )
    } yield ()
  }

  test("update-docstrings".tag(FlakyWindows)) {
    for {
      _ <- server.initialize(
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
        """|```scala
           |def foo(x: Int): Int
           |```
           |test
           |""".stripMargin.hover
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
        """|```scala
           |def foo(x: Int): Int
           |```
           |test2
           |""".stripMargin.hover
      )
    } yield ()
  }

  test("dependencies".tag(FlakyWindows)) {
    for {
      _ <- server.initialize(
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
        """override def head: Int""".hover,
        root = workspace.resolve(Directories.readonly)
      )
    } yield ()
  }

}
