package tests

import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.UserConfiguration

import ch.epfl.scala.bsp4j.DebugSessionParams
import com.google.gson.JsonObject
import org.eclipse.lsp4j.Command

class CodeLensLspSuite extends BaseCodeLensLspSuite("codeLenses") {

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

  check("test-suite-class", library = Some("org.scalatest::scalatest:3.2.4"))(
    """|package foo.bar
       |<<test>><<debug test>>
       |class Foo extends org.scalatest.funsuite.AnyFunSuite {
       |}
       |""".stripMargin
  )

  checkTestCases(
    "test-suite-with-tests",
    library = Some("org.scalatest::scalatest:3.2.4"),
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

  check("test-suite-object", library = Some("com.lihaoyi::utest:0.7.3"))(
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

  private val scalaCliScriptPath = "a/src/main/scala/a/main.sc"
  test("run-script") {
    cleanWorkspace()
    for {

      _ <- initialize(
        s"""/.bsp/scala-cli.json
           |${BaseScalaCliSuite.scalaCliBspJsonContent()}
           |/.scala-build/ide-inputs.json
           |${BaseScalaCliSuite.scalaCliIdeInputJson(".")}
           |/$scalaCliScriptPath
           |print("oranges are nice")""".stripMargin
      )
      _ <- server.didOpen(scalaCliScriptPath)
      _ <- assertCodeLenses(
        scalaCliScriptPath,
        """|<<run>><<debug>>
           |print("oranges are nice")
           |""".stripMargin,
      )
    } yield ()
  }

  private val scalaCliScriptPathTop = "main.sc"
  test("run-script-top") {
    cleanWorkspace()
    for {

      _ <- initialize(
        s"""/.bsp/scala-cli.json
           |${BaseScalaCliSuite.scalaCliBspJsonContent()}
           |/.scala-build/ide-inputs.json
           |${BaseScalaCliSuite.scalaCliIdeInputJson(".")}
           |/$scalaCliScriptPathTop
           |print("oranges are nice")""".stripMargin
      )
      _ <- server.didOpen(scalaCliScriptPathTop)
      _ <- assertCodeLenses(
        scalaCliScriptPathTop,
        """|<<run>><<debug>>
           |print("oranges are nice")
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
      _ <- server.didSave("a/src/main/scala/Main.scala")(text =>
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

  test("run-shell-command") {

    def runFromCommand(cmd: Command) = {

      cmd.getArguments().asScala.toList match {
        case (params: DebugSessionParams) :: _ =>
          params.getData() match {
            case obj: JsonObject =>
              val cmd = obj.get("shellCommand").getAsString().split("\\s+")
              ShellRunner
                .runSync(cmd.toList, workspace, redirectErrorOutput = false)
                .map(_.trim())
                .orElse {
                  scribe.error(
                    "Couldn't run command specified in shellCommand."
                  )
                  scribe.error("The command run was:\n" + cmd.mkString(" "))
                  None
                }
            case _ => None
          }

        case _ => None
      }
    }
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/metals.json
            |{
            |  "a": {}
            |}
            |/a/src/main/scala/a/Main.scala
            |package foo
            |
            |object Main {
            |  def main(args: Array[String]): Unit = {
            |     println("Hello java!")
            |  }
            |}
            |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      lenses <- server.codeLenses("a/src/main/scala/a/Main.scala")
      _ = assert(lenses.size > 0, "No lenses were generated!")
      command = lenses.head.getCommand()
      _ = assertEquals(runFromCommand(command), Some("Hello java!"))
    } yield ()

  }
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
