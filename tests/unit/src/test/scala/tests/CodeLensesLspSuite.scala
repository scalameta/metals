package tests
import scala.concurrent.Future
import munit.Location
import munit.TestOptions

class CodeLensesLspSuite extends BaseLspSuite("codeLenses") {
  check("empty-package")(
    """|<<run>><<debug>>
       |object Main {
       |  def main(args: Array[String]): Unit = {}
       |}
       |""".stripMargin
  )

  check("class")(
    """|class Main {
       |  def main(args: Array[String]): Unit = {}
       |}
       |""".stripMargin
  )

  check("main")(
    """|package foo
       |<<run>><<debug>>
       |object Main {
       |  def main(args: Array[String]): Unit = {}
       |}
       |""".stripMargin
  )

  check("non-ascii")(
    """|package foo.bar
       |<<run>><<debug>>
       |object :: {
       |  def main(args: Array[String]): Unit = {}
       |}
       |""".stripMargin
  )

  check("test-suite-class", library = "org.scalatest::scalatest:3.0.5")(
    """|package foo.bar
       |<<test>><<debug test>>
       |class Foo extends org.scalatest.FunSuite {
       |  test("foo") {}
       |}
       |""".stripMargin
  )

  check("test-suite-object", library = "com.lihaoyi::utest:0.7.3")(
    """|package foo.bar
       |<<test>><<debug test>>
       |object Foo extends utest.TestSuite {
       |<< tests>>
       |val tests = utest.Tests {}
       |}
       |""".stripMargin
  )

  test("run-many-main-files") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        """|/metals.json
           |{
           |  "a": { }
           |}
           |
           |/a/src/main/scala/Foo.scala
           |package foo.bar
           |object Foo {
           |  def main(args: Array[String]): Unit = {}
           |}
           |
           |/a/src/main/scala/Bar.scala
           |package foo.bar
           |object Bar {
           |  def main(args: Array[String]): Unit = {}
           |}
           |""".stripMargin
      )
      _ <- server.didOpen(
        "a/src/main/scala/Foo.scala"
      ) // compile `a` to populate its cache
      _ <- assertCodeLenses(
        "a/src/main/scala/Foo.scala",
        """|package foo.bar
           |<<run>><<debug>>
           |object Foo {
           |  def main(args: Array[String]): Unit = {}
           |}
           |""".stripMargin
      )
      _ <- assertCodeLenses(
        "a/src/main/scala/Bar.scala",
        """|package foo.bar
           |<<run>><<debug>>
           |object Bar {
           |  def main(args: Array[String]): Unit = {}
           |}
           |""".stripMargin
      )
    } yield ()
  }

  // Tests, whether main class in one project does not affect other class with same name in other project
  test("run-multi-module") {
    cleanWorkspace()
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
      _ <- assertNoCodeLenses("b/src/main/scala/Main.scala")
    } yield ()
  }

  test("remove-stale-lenses") {
    cleanWorkspace()
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
        """<<run>><<debug>>
          |object Main {
          |  def main(args: Array[String]): Unit = {}
          |}
          |""".stripMargin
      )
      _ <- server.didSave("a/src/main/scala/Main.scala")(text =>
        text.replace("object Main", "class Main")
      )
      _ <- assertNoCodeLenses("a/src/main/scala/Main.scala")

    } yield ()
  }

  test("keep-after-error") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        """|/metals.json
           |{ "a": { } }
           |
           |/a/src/main/scala/Main.scala
           |object Main {
           |  def main(args: Array[String]): Unit = ???
           |}""".stripMargin
      )
      _ <- assertCodeLenses(
        "a/src/main/scala/Main.scala",
        """<<run>><<debug>>
          |object Main {
          |  def main(args: Array[String]): Unit = ???
          |}
          |""".stripMargin
      )
      _ <- server.didSave("a/src/main/scala/Main.scala")(text =>
        text.replace("}", "")
      )
      _ <- assertCodeLenses(
        "a/src/main/scala/Main.scala",
        """<<run>><<debug>>
          |object Main {
          |  def main(args: Array[String]): Unit = ???
          |
          |""".stripMargin
      )
    } yield ()
  }

  check("go-to-super-method-lenses")(
    """package gameofthrones
      |
      |abstract class Lannister {
      |  def payTheirDebts: Boolean
      |  def trueLannister = payTheirDebts
      |}
      |
      |trait Tywin extends Lannister{
      |<< payTheirDebts>>
      |override def payTheirDebts = true
      |}
      |
      |trait Jamie extends Tywin {
      |<< payTheirDebts>>
      |override def payTheirDebts = true
      |}
      |
      |trait Tyrion extends Tywin {
      |<< payTheirDebts>>
      |override def payTheirDebts = true
      |}
      |
      |trait Cersei extends Tywin {
      |<< payTheirDebts>>
      |override def payTheirDebts = false
      |<< trueLannister>>
      |override def trueLannister = false
      |}
      |
      |class Joffrey extends Lannister with Jamie with Cersei {
      |<< payTheirDebts>>
      |override def payTheirDebts = false
      |}
      |
      |class Tommen extends Lannister with Cersei with Jamie {
      |<< payTheirDebts>>
      |override def payTheirDebts = true
      |}
      |""".stripMargin
  )

  check("go-to-super-method-lenses-anonymous-class")(
    """package a
      |
      |class A { def afx(): Unit = ??? }
      |object X {
      |  val t = new A {
      |<< afx>>
      |override def afx(): Unit = ???
      |  }
      |}
      |
    """.stripMargin
  )

  def check(name: TestOptions, library: String = "")(
      expected: String
  )(implicit loc: Location): Unit = {
    test(name) {
      cleanWorkspace()
      val original = expected.replaceAll("<<.*>>\\W+", "")

      val sourceFile = {
        val file = """package (.*).*""".r
          .findFirstMatchIn(original)
          .map(_.group(1))
          .map(packageName => packageName.replaceAll("\\.", "/"))
          .map(packageDir => s"$packageDir/Foo.scala")
          .getOrElse("Foo.scala")

        s"a/src/main/scala/$file"
      }

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
              |/$sourceFile
              |$original
              |""".stripMargin
        )
        _ <- assertCodeLenses(sourceFile, expected)
      } yield ()
    }
  }

  private def assertCodeLenses(
      relativeFile: String,
      expected: String,
      maxRetries: Int = 4
  )(implicit loc: Location): Future[Unit] = {
    val obtained = server.codeLenses(relativeFile)(maxRetries).recover {
      case _: NoSuchElementException =>
        server.textContents(relativeFile)
    }

    obtained.map(assertNoDiff(_, expected))
  }

  private def assertNoCodeLenses(
      relativeFile: String,
      maxRetries: Int = 4
  ): Future[Unit] = {
    server.codeLenses(relativeFile)(maxRetries).failed.flatMap {
      case _: NoSuchElementException => Future.unit
      case e => Future.failed(e)
    }
  }
}
