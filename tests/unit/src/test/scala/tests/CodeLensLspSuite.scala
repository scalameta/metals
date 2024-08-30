package tests

import scala.meta.internal.metals.UserConfiguration

import coursierapi.JvmManager

class CodeLensLspSuite extends BaseCodeLensLspSuite("codeLenses") {
  override protected val changeSpacesToDash = false
  override def userConfig: UserConfiguration =
    super.userConfig.copy(superMethodLensesEnabled = true)

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

  check("test-suite-class", library = Some("org.scalatest::scalatest:3.2.16"))(
    """|package foo.bar
       |<<test>><<debug test>>
       |class Foo extends org.scalatest.funsuite.AnyFunSuite {
       |}
       |""".stripMargin
  )

  check(
    "custom-main",
    library = Some("io.github.dfianthdl::dfhdl:0.7.1"),
    plugin = Some("io.github.dfianthdl:::dfhdl-plugin:0.7.1"),
    scalaVersion = Some("3.5.0"),
  )(
    """|<<run (top_Foo)>><<debug (top_Foo)>>
       |package foo.bar
       |
       |import dfhdl.* 
       |@top class Foo extends EDDesign
       |
       |<<run>><<debug>>
       |@main def hey = ???
       |""".stripMargin
  )

  checkTestCases(
    "test-suite-with-tests",
    library = Some("org.scalatest::scalatest:3.2.16"),
    minExpectedLenses = 6,
  )(
    """|package foo.bar
       |<<test>><<debug test>>
       |class Foo extends org.scalatest.funsuite.AnyFunSuite {
       |<<test case>><<debug test case>>
       |  test("foo") {
       |    assert(1 == 1)
       |  }
       |<<test case>><<debug test case>>
       |  test("bar") {
       |    assert(1 == 1)
       |  }
       |}
       |""".stripMargin
  )

  check(
    "test-suite-object",
    library = Some("com.lihaoyi::utest:0.7.3"),
    minExpectedLenses = 3,
  )(
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
      _ <- initialize(
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
           |""".stripMargin,
      )
      _ <- assertCodeLenses(
        "a/src/main/scala/Bar.scala",
        """|package foo.bar
           |<<run>><<debug>>
           |object Bar {
           |  def main(args: Array[String]): Unit = {}
           |}
           |""".stripMargin,
      )
    } yield ()
  }
  test("run-java") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{
           |  "a": { }
           |}
           |
           |/a/src/main/java/Main.java
           |package foo.bar;
           |
           |public class Main {
           |  public static void main(String[] args){
           |    System.out.println("Hello from Java!");
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen(
        "a/src/main/java/Main.java"
      ) // compile `a` to populate its cache
      _ <- assertCodeLenses(
        "a/src/main/java/Main.java",
        """|package foo.bar;
           |
           |public class Main {
           |<<run>><<debug>>
           |  public static void main(String[] args){
           |    System.out.println("Hello from Java!");
           |  }
           |}
           |""".stripMargin,
      )
    } yield ()
  }

  // Tests, whether main class in one project does not affect other class with same name in other project
  test("run-multi-module") {
    cleanWorkspace()
    for {
      _ <- initialize(
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
      _ <- initialize(
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
          |""".stripMargin,
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
      _ <- initialize(
        """|/metals.json
           |{ "a": { } }
           |
           |/a/src/main/scala/Main.scala
           |object Main {
           |  def main(args: Array[String]): Unit = ???
           |}""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/Main.scala")
      _ <- assertCodeLenses(
        "a/src/main/scala/Main.scala",
        """<<run>><<debug>>
          |object Main {
          |  def main(args: Array[String]): Unit = ???
          |}
          |""".stripMargin,
      )
      _ <- server.didChange("a/src/main/scala/Main.scala")(text =>
        text.replace("}", "")
      )
      _ <- assertCodeLenses(
        "a/src/main/scala/Main.scala",
        """<<run>><<debug>>
          |object Main {
          |  def main(args: Array[String]): Unit = ???
          |
          |""".stripMargin,
      )
    } yield ()
  }

  check("go-to-super-method-lenses", printCommand = true)(
    """package gameofthrones
      |
      |abstract class Lannister {
      |  def payTheirDebts: Boolean
      |  def trueLannister = payTheirDebts
      |}
      |
      |trait Tywin extends Lannister{
      |<< payTheirDebts goto["gameofthrones/Lannister#payTheirDebts()."]>>
      |override def payTheirDebts = true
      |}
      |
      |trait Jamie extends Tywin {
      |<< payTheirDebts goto["gameofthrones/Tywin#payTheirDebts()."]>>
      |override def payTheirDebts = true
      |}
      |
      |trait Tyrion extends Tywin {
      |<< payTheirDebts goto["gameofthrones/Tywin#payTheirDebts()."]>>
      |override def payTheirDebts = true
      |}
      |
      |trait Cersei extends Tywin {
      |<< payTheirDebts goto["gameofthrones/Tywin#payTheirDebts()."]>>
      |override def payTheirDebts = false
      |<< trueLannister goto["gameofthrones/Lannister#trueLannister()."]>>
      |override def trueLannister = false
      |}
      |
      |class Joffrey extends Lannister with Jamie with Cersei {
      |<< payTheirDebts goto["gameofthrones/Cersei#payTheirDebts()."]>>
      |override def payTheirDebts = false
      |}
      |
      |class Tommen extends Lannister with Cersei with Jamie {
      |<< payTheirDebts goto["gameofthrones/Jamie#payTheirDebts()."]>>
      |override def payTheirDebts = true
      |}
      |""".stripMargin
  )

  check("go-to-super-method-lenses-anonymous-class", printCommand = true)(
    s"""|package a
        |
        |object X {
        |  def main = {
        |    class A { def afx(): Unit = ??? } 
        |    val t = new A {
        |<< afx goto-position[{"uri":"${workspace.toURI}a/src/main/scala/a/Foo.scala","range":{"start":{"line":4,"character":18},"end":{"line":4,"character":21}}}]>>
        |    override def afx(): Unit = ???
        |    }
        |  }
        |}
        |""".stripMargin
  )

  testRunShellCommand(
    "run-shell-command-old-java",
    Some(JvmManager.create().get("8").toString()),
  )
  testRunShellCommand("run-shell-command")
  testRunShellCommand("run shell command")

  test("no-stale-supermethod-lenses") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/metals.json
            |{
            |  "a": {}
            |}
            |/a/src/main/scala/a/A.scala
            |package a
            |trait X {
            |  def foo: Int
            |}
            |case class Y(foo: Int) extends X
            |
            |trait Z {
            |  def bar: Int
            |}
            |case class W(bar: Int) extends Z
            |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ <- assertCodeLenses(
        "a/src/main/scala/a/A.scala",
        """|package a
           |trait X {
           |  def foo: Int
           |}
           |<< foo>>
           |case class Y(foo: Int) extends X
           |
           |trait Z {
           |  def bar: Int
           |}
           |<< bar>>
           |case class W(bar: Int) extends Z
           |""".stripMargin,
      )
      _ <- server.didChange("a/src/main/scala/a/A.scala") { _ =>
        s"""|package a
            |trait X {
            |  def foo: Int
            |}
            |//case class Y(foo: Int) extends X
            |
            |trait Z {
            |  def bar: Int
            |}
            |case class W(bar: Int) extends Z
            |""".stripMargin
      }
      _ <- assertCodeLenses(
        "a/src/main/scala/a/A.scala",
        """|package a
           |trait X {
           |  def foo: Int
           |}
           |//case class Y(foo: Int) extends X
           |
           |trait Z {
           |  def bar: Int
           |}
           |<< bar>>
           |case class W(bar: Int) extends Z
           |""".stripMargin,
      )
    } yield ()
  }
}
