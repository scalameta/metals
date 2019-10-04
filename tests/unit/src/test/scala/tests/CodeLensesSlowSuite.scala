package tests
import scala.concurrent.Future

object CodeLensesSlowSuite extends BaseSlowSuite("codeLenses") {
  check("main")(
    """<<run>>
      |object Main {
      |  def main(args: Array[String]): Unit = {}
      |}
      |""".stripMargin
  )

  check("non-ascii")(
    """<<run>>
      |object :: {
      |  def main(args: Array[String]): Unit = {}
      |}
      |""".stripMargin
  )

  check("test-suite-class", library = "org.scalatest::scalatest:3.0.5")(
    """|<<test>>
       |class Foo extends org.scalatest.FunSuite {
       |  test("foo") {}
       |}
       |""".stripMargin
  )

  check("test-suite-object", library = "com.lihaoyi::utest:0.7.1")(
    """|<<test>>
       |object Foo extends utest.TestSuite {
       |  val tests = utest.Tests {}
       |}
       |""".stripMargin
  )

  def check(name: String, library: String = "")(expected: String): Unit =
    testAsync(name) {
      val original = expected.replaceAll("<<.*>>", "")
      val dependencies =
        if (library.isEmpty) ""
        else s""""libraryDependencies": [ "$library" ]"""

      for {
        _ <- server.initialize(
          s"""|/metals.json
              |{
              |  "a": { $dependencies }
              |}
              |
              |/a/src/main/scala/Foo.scala
              |$original
              |""".stripMargin
        )
        _ <- assertCodeLenses("a/src/main/scala/Foo.scala", expected)
      } yield ()
    }

  private def assertCodeLenses(
      relativeFile: String,
      expected: String
  ): Future[Unit] = {
    val path = server.toPath(relativeFile)
    for {
      _ <- server.server.compilations.compileFiles(List(path))
      _ <- server.server.compilations.compileFiles(List(path))
      obtained <- server.codeLenses(relativeFile)
    } yield assertNoDiff(obtained, expected)
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
           |object Main
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/Main.scala") // compile `a` to populate its cache
      _ <- assertCodeLenses(
        "b/src/main/scala/Main.scala",
        """|object Main
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
}
