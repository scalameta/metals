package tests
import scala.concurrent.Future
import munit.Location
import org.eclipse.{lsp4j => l}
import scala.meta.internal.metals.MetalsEnrichments._

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
       |  val tests = utest.Tests {}
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
      _ <- server.didOpen("a/src/main/scala/Foo.scala") // compile `a` to populate its cache
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

  test("go-to-parent-method-lenses") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        """|/metals.json
           |{ "a": { } }
           |
           |/a/src/main/scala/Lannister.scala
           |abstract class Lannister {
           |  def payTheirDebts: Boolean
           |  def trueLannister = payTheirDebts
           |}
           |
           |trait Tywin extends Lannister{
           |  override def payTheirDebts = true
           |}
           |
           |trait Jamie extends Tywin {
           |  override def payTheirDebts = true
           |}
           |
           |trait Tyrion extends Tywin {
           |  override def payTheirDebts = true
           |}
           |
           |trait Cersei extends Tywin {
           |  override def payTheirDebts = false
           |}
           |
           |class Joffrey extends Lannister with Jamie with Cersei {
           |  override def payTheirDebts = false
           |}
           |
           |class Tommen extends Lannister with Cersei with Jamie {
           |  override def payTheirDebts = true
           |}""".stripMargin
      )
      _ <- assertCodeLenses(
        "a/src/main/scala/Lannister.scala",
        """abstract class Lannister {
          |  def payTheirDebts: Boolean
          |  def trueLannister = payTheirDebts
          |}
          |
          |trait Tywin extends Lannister{
          |<<Parent payTheirDebts>>
          |  override def payTheirDebts = true
          |}
          |
          |trait Jamie extends Tywin {
          |<<Parent payTheirDebts>>
          |  override def payTheirDebts = true
          |}
          |
          |trait Tyrion extends Tywin {
          |<<Parent payTheirDebts>>
          |  override def payTheirDebts = true
          |}
          |
          |trait Cersei extends Tywin {
          |<<Parent payTheirDebts>>
          |  override def payTheirDebts = false
          |}
          |
          |class Joffrey extends Lannister with Jamie with Cersei {
          |<<Parent payTheirDebts>>
          |  override def payTheirDebts = false
          |}
          |
          |class Tommen extends Lannister with Cersei with Jamie {
          |<<Parent payTheirDebts>>
          |  override def payTheirDebts = true
          |}
          |""".stripMargin
      )
    } yield ()
  }

  test("go-to-parent-method-command") {
    cleanWorkspace()

    for {
      _ <- server.initialize(
        s"""|/metals.json
            |{
            |  "a": { }
            |}
            |
            |/a/src/main/scala/a/Main.scala
            |
            |package a
            |
            |class A {
            |  def afx(): Unit = ()
            |  val zm: String = "A"
            |}
            |
            |class B extends A {
            |  override def afx(): Unit = ()
            |  override val zm: String = "B"
            |}
            |
            |class C extends B {
            |  override def afx(): Unit = ()
            |}
            |
            |class D extends C {
            |  override val zm: String = "D"
            |}
            |
            |class E extends D { }
            |
            |object X {
            |  val t = new E {
            |    override def afx(): Unit = ()
            |    override val zm: String = "anon"
            |  }
            |}
            |
            |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      lenses = server.codeLensesTips("a/src/main/scala/a/Main.scala")
      _ = pprint.log(s"LENSES ${lenses}")
      formattedLenses = lenses.map(lens => (lens.getCommand.getTitle, lens.getCommand.getArguments.asScala.head.asInstanceOf[l.Location].getRange))
      _ = assert(formattedLenses.contains(("Parent afx", new l.Range(new l.Position(4, 6), new l.Position(4, 9)))))
      _ = assert(formattedLenses.contains(("Parent afx", new l.Range(new l.Position(9, 15), new l.Position(9, 18)))))
      _ = assert(formattedLenses.contains(("Parent zm", new l.Range(new l.Position(10, 15), new l.Position(10, 17)))))
      _ = assert(formattedLenses.contains(("Parent zm", new l.Range(new l.Position(18, 15), new l.Position(18, 17)))))
    } yield ()
  }

  def check(name: String, library: String = "")(
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
  )(implicit loc: Location): Future[Unit] = {
    server.codeLenses(relativeFile)(maxRetries).failed.flatMap {
      case _: NoSuchElementException => Future.unit
      case e => Future.failed(e)
    }
  }
}
