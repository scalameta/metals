package tests
import scala.concurrent.Future

object CodeLensesSlowSuite extends BaseSlowSuite("codeLenses") {
  testAsync("run") {
    for {
      _ <- server.initialize(
        """|/metals.json
           |{
           |  "a": { }
           |}
           |
           |/a/src/main/scala/Main.scala
           |object Main {
           |  def main(args: Array[String]): Unit = {}
           |}""".stripMargin
      )
      _ <- assertCodeLenses(
        "a/src/main/scala/Main.scala",
        """<<run>>
          |object Main {
          |  def main(args: Array[String]): Unit = {}
          |}
          |""".stripMargin
      )
    } yield ()
  }

  testAsync("run-many-main-files") {
    for {
      _ <- server.initialize(
        """|/metals.json
           |{
           |  "a": { }
           |}
           |
           |/a/src/main/scala/Foo.scala
           |object Foo {
           |  def main(args: Array[String]): Unit = {}
           |}
           |
           |/a/src/main/scala/Bar.scala
           |object Bar {
           |  def main(args: Array[String]): Unit = {}
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/Foo.scala") // compile `a` to populate its cache
      _ <- assertCodeLenses(
        "a/src/main/scala/Foo.scala",
        """<<run>>
          |object Foo {
          |  def main(args: Array[String]): Unit = {}
          |}
          |""".stripMargin
      )
      _ <- assertCodeLenses(
        "a/src/main/scala/Bar.scala",
        """<<run>>
          |object Bar {
          |  def main(args: Array[String]): Unit = {}
          |}
          |""".stripMargin
      )
    } yield ()
  }

  // Tests, whether main class in one project does not affect other class with same name in other project
  testAsync("run-multi-module") {
    for {
      _ <- server.initialize(
        """|/metals.json
           |{
           |  "a": { },
           |  "b": { }
           |}
           |
           |/a/src/main/scala/Main.scala
           |object Main {
           |  def main(args: Array[String]): Unit = {}
           |}
           |
           |/b/src/main/scala/Main.scala
           |object Main {}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/Main.scala") // compile `a` to populate its cache
      _ <- assertCodeLenses(
        "b/src/main/scala/Main.scala",
        """|object Main {}
           |""".stripMargin
      )
    } yield ()
  }

  testAsync("remove-stale-lenses") {
    for {
      _ <- server.initialize(
        """|/metals.json
           |{
           |  "a": { }
           |}
           |
           |/a/src/main/scala/Main.scala
           |object Main {
           |  def main(args: Array[String]): Unit = {}
           |}""".stripMargin
      )
      _ <- assertCodeLenses(
        "a/src/main/scala/Main.scala",
        """<<run>>
          |object Main {
          |  def main(args: Array[String]): Unit = {}
          |}
          |""".stripMargin
      )
      _ <- server.didSave("a/src/main/scala/Main.scala")(
        text => text.replace("object Main", "class Main")
      )
      _ <- assertCodeLenses(
        "a/src/main/scala/Main.scala",
        """class Main {
          |  def main(args: Array[String]): Unit = {}
          |}
          |""".stripMargin
      )

    } yield ()
  }

  private def assertCodeLenses(
      filename: String,
      expected: String
  ): Future[Unit] =
    for {
      _ <- server.didOpen(filename)
      _ <- server.didOpen(filename) // first compilation could have not yet persisted analysis
      obtained <- server.codeLenses(filename)
    } yield {
      assertNoDiff(obtained, expected)
    }
}
