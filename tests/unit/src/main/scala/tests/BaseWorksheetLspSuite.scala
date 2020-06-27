package tests

import scala.concurrent.Promise

import scala.meta.internal.metals.InitializationOptions
import scala.meta.internal.metals.MetalsSlowTaskResult
import scala.meta.internal.metals.UserConfiguration

abstract class BaseWorksheetLspSuite(scalaVersion: String)
    extends BaseLspSuite("worksheet") {
  override def initializationOptions: Option[InitializationOptions] =
    Some(InitializationOptions.Default.copy(decorationProvider = true))

  override def userConfig: UserConfiguration =
    super.userConfig.copy(worksheetScreenWidth = 40, worksheetCancelTimeout = 1)

  override def munitIgnore: Boolean = !isValidScalaVersionForEnv(scalaVersion)

  test("completion") {
    assume(!isWindows, "This test is flaky on Windows")
    for {
      _ <- server.initialize(
        s"""
           |/metals.json
           |{
           |  "a": {
           |    "scalaVersion": "$scalaVersion",
           |    "libraryDependencies": ["com.lihaoyi::sourcecode:0.1.8"]
           |  }
           |}
           |/a/src/main/scala/foo/Main.worksheet.sc
           |identity(42)
           |val name = sourcecode.Name.generate.value
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/foo/Main.worksheet.sc")
      _ <- server.didSave("a/src/main/scala/foo/Main.worksheet.sc")(identity)
      identity <- server.completion(
        "a/src/main/scala/foo/Main.worksheet.sc",
        "identity@@"
      )
      _ = assertNoDiff(identity, "identity[A](x: A): A")
      generate <- server.completion(
        "a/src/main/scala/foo/Main.worksheet.sc",
        "generate@@"
      )
      _ = assertNoDiff(generate, "generate: Name")
      _ = assertNoDiagnostics()
      _ = assertNoDiff(
        client.workspaceDecorations,
        """|identity(42) // 42
           |val name = sourcecode.Name.generate.value // "name"
           |""".stripMargin
      )
    } yield ()
  }

  test("completion-imports") {
    for {
      _ <- server.initialize(
        s"""
           |/metals.json
           |{
           |  "a": {}
           |}
           |/a/src/main/scala/foo/Main.worksheet.sc
           |import $$dep.`com.lihaoyi::scalatags:0.9.0`
           |import scalatags.Text.all._
           |
           |val htmlFile = html(
           |  body(
           |    p("This is a big paragraph of text")
           |  )
           |)
           |
           |htmlFile.render
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/foo/Main.worksheet.sc")
      _ <- server.didSave("a/src/main/scala/foo/Main.worksheet.sc")(identity)
      identity <- server.completion(
        "a/src/main/scala/foo/Main.worksheet.sc",
        "htmlFile.render@@"
      )
      _ = assertNoDiff(identity, "render: String")
      _ = assertNoDiagnostics()
      _ = assertNoDiff(
        client.workspaceDecorations,
        """|import $dep.`com.lihaoyi::scalatags:0.9.0`
           |import scalatags.Text.all._
           |
           |val htmlFile = html(
           |  body(
           |    p("This is a big paragraph of text")
           |  )
           |) // TypedTag("html",List(WrappedArray(TypedTag("body",List(WrappedArray(TypedTag("p", List(WrappedArray(StringFrag("This is…
           |
           |htmlFile.render // "<html><body><p>This is a big paragraph of text</p></body></html>"
           |""".stripMargin
      )
    } yield ()
  }

  test("outside-target") {
    for {
      _ <- server.initialize(
        s"""
           |/metals.json
           |{"a": {"scalaVersion": "$scalaVersion"}}
           |/a/Main.worksheet.sc
           |import java.nio.file.Files
           |val name = "Susan"
           |val greeting = s"Hello $$name"
           |println(greeting + "\\nHow are you?")
           |1.to(10).toVector
           |val List(a, b) = List(42, 10)
           |""".stripMargin
      )
      _ <- server.didOpen("a/Main.worksheet.sc")
      _ = assertNoDiagnostics()
      _ = assertNoDiff(
        client.workspaceDecorations,
        """|
           |import java.nio.file.Files
           |val name = "Susan" // "Susan"
           |val greeting = s"Hello $name" // "Hello Susan"
           |println(greeting + "\nHow are you?") // Hello Susan…
           |1.to(10).toVector // Vector(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
           |val List(a, b) = List(42, 10) // a=42, b=10
           |""".stripMargin
      )
    } yield ()
  }

  test("render") {
    for {
      _ <- server.initialize(
        s"""
           |/metals.json
           |{"a": {"scalaVersion": "$scalaVersion"}}
           |/a/src/main/scala/Main.worksheet.sc
           |import java.nio.file.Files
           |val name = "Susan"
           |val greeting = s"Hello $$name"
           |println(greeting + "\\nHow are you?")
           |1.to(10).toVector
           |val List(a, b) = List(42, 10)
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/Main.worksheet.sc")
      _ = assertNoDiagnostics()
      _ = assertNoDiff(
        client.workspaceDecorations,
        """|
           |import java.nio.file.Files
           |val name = "Susan" // "Susan"
           |val greeting = s"Hello $name" // "Hello Susan"
           |println(greeting + "\nHow are you?") // Hello Susan…
           |1.to(10).toVector // Vector(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
           |val List(a, b) = List(42, 10) // a=42, b=10
           |""".stripMargin
      )
      _ = assertNoDiff(
        client.workspaceDecorationHoverMessage,
        """|import java.nio.file.Files
           |val name = "Susan"
           |```scala
           |name: String = "Susan"
           |```
           |val greeting = s"Hello $name"
           |```scala
           |greeting: String = "Hello Susan"
           |```
           |println(greeting + "\nHow are you?")
           |```scala
           |// Hello Susan
           |// How are you?
           |```
           |1.to(10).toVector
           |```scala
           |res1: Vector[Int] = Vector(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
           |```
           |val List(a, b) = List(42, 10)
           |```scala
           |a: Int = 42
           |b: Int = 10
           |```
           |""".stripMargin
      )
    } yield ()
  }

  test("cancel") {
    val cancelled = Promise[Unit]()
    client.slowTaskHandler = { params =>
      cancelled.trySuccess(())
      Some(MetalsSlowTaskResult(cancel = true))
    }
    for {
      _ <- server.initialize(
        s"""
           |/metals.json
           |{"a": {"scalaVersion": "$scalaVersion"}}
           |/a/src/main/scala/Main.worksheet.sc
           |println(42)
           |Stream.from(10).last
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/Main.worksheet.sc")
      _ <- cancelled.future
      _ = client.slowTaskHandler = (_ => None)
      _ <- server.didSave("a/src/main/scala/Main.worksheet.sc")(
        _.replace("Stream", "// Stream")
      )
      _ <- server.didSave("a/src/main/scala/Main.worksheet.sc")(
        _.replace("42", "43")
      )
      _ = assertNoDiff(
        client.workspaceDecorations,
        """|
           |println(43) // 43
           |// Stream.from(10).last
           |""".stripMargin
      )
    } yield ()
  }

  test("crash") {
    for {
      _ <- server.initialize(
        s"""
           |/metals.json
           |{"a": {"scalaVersion": "$scalaVersion"}}
           |/a/src/main/scala/Main.worksheet.sc
           |val x = 42
           |throw new RuntimeException("boom")
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/Main.worksheet.sc")
      _ = assertNoDiff(
        client.workspaceDecorations,
        """|
           |val x = 42 // 42
           |throw new RuntimeException("boom")
           |""".stripMargin
      )
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/scala/Main.worksheet.sc:2:1: error: java.lang.RuntimeException: boom
           |	at repl.MdocSession$App.<init>(Main.worksheet.sc:11)
           |	at repl.MdocSession$.app(Main.worksheet.sc:3)
           |
           |throw new RuntimeException("boom")
           |^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
           |""".stripMargin
      )
    } yield ()
  }

  test("dependsOn") {
    for {
      _ <- server.initialize(
        s"""
           |/metals.json
           |{
           |  "a": {"scalaVersion": "$scalaVersion"},
           |  "b": {"dependsOn": ["a"], "scalaVersion": "$scalaVersion"}
           |}
           |/a/src/main/scala/core/Lib.scala
           |package core
           |case object Lib
           |/b/src/main/scala/core/Lib2.scala
           |package core
           |case object Lib2
           |/b/src/main/scala/foo/Main.worksheet.sc
           |println(core.Lib)
           |println(core.Lib2)
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/core/Lib.scala")
      _ <- server.didOpen("b/src/main/scala/core/Lib2.scala")
      _ <- server.didOpen("b/src/main/scala/foo/Main.worksheet.sc")
      _ = assertNoDiagnostics()
      _ = assertNoDiff(
        client.workspaceDecorations,
        """|println(core.Lib) // Lib
           |println(core.Lib2) // Lib2
           |""".stripMargin
      )
    } yield ()
  }

  test("no-worksheet".flaky) {
    for {
      _ <- server.initialize(
        s"""|/metals.json
            |{"a": {"scalaVersion": "$scalaVersion"}}
            |/a/src/main/scala/Main.sc
            |identity(42)
            |val x: Int = ""
            |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/Main.sc")
      _ = assertNoDiagnostics()
      identity <- server.completion(
        "a/src/main/scala/Main.sc",
        "identity@@"
      )
      // completions work despite error
      _ = assertNoDiff(identity, "identity[A](x: A): A")
      // decorations do not appear for non ".worksheet.sc" files.
      _ = assertNoDiff(client.workspaceDecorations, "")
    } yield ()
  }

  test("update-classpath") {
    client.slowTaskHandler = _ => None
    for {
      _ <- server.initialize(
        s"""
           |/metals.json
           |{"a": {"scalaVersion": "$scalaVersion"}}
           |/a/src/main/scala/a/Util.scala
           |package a
           |object Util {
           |  def increase(n: Int): Int = n + 1
           |}
           |/a/src/main/scala/a/Main.worksheet.sc
           |a.Util.increase(1)
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Util.scala")
      _ <- server.didOpen("a/src/main/scala/a/Main.worksheet.sc")
      _ = assertNoDiagnostics()
      _ = assertNoDiff(
        client.workspaceDecorations,
        """
          |a.Util.increase(1) // 2
          |""".stripMargin
      )
      _ <- server.didSave("a/src/main/scala/a/Util.scala")(
        _.replace("n + 1", "n + 2")
      )
      _ <- server.didSave("a/src/main/scala/a/Main.worksheet.sc")(identity)
      _ = assertNoDiff(
        client.workspaceDecorations,
        """
          |a.Util.increase(1) // 3
          |""".stripMargin
      )
    } yield ()
  }

  test("syntax-error") {
    for {
      _ <- server.initialize(
        s"""|/metals.json
            |{"a": {"scalaVersion": "$scalaVersion"}}
            |/a/src/main/scala/a/Main.worksheet.sc
            |val x: Int = ""
            |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Main.worksheet.sc")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/scala/a/Main.worksheet.sc:1:14: error: type mismatch;
           | found   : String("")
           | required: Int
           |val x: Int = ""
           |             ^^
           |""".stripMargin
      )
      _ <- server.didChange("a/src/main/scala/a/Main.worksheet.sc")(
        _.replace("val x", "def y = \nval x")
      )
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/scala/a/Main.worksheet.sc:2:1: error: illegal start of simple expression
           |val x: Int = ""
           |^^^
           |a/src/main/scala/a/Main.worksheet.sc:2:14: error: type mismatch;
           | found   : String("")
           | required: Int
           |val x: Int = ""
           |             ^^
           |""".stripMargin
      )
    } yield ()
  }

  test("definition") {
    // NOTE(olafur) this test fails unpredicatly on Windows with
    //      """|/a/src/main/scala/Main.worksheet.sc
    //         |val message/*<no symbol>*/ = "Hello World!"
    //         |println/*<no symbol>*/(message/*<no symbol>*/)
    assume(!isWindows, "This test fails unpredictably on Window")
    for {
      _ <- server.initialize(
        s"""
           |/metals.json
           |{"a": {"scalaVersion": "$scalaVersion"}}
           |/a/src/main/scala/Main.worksheet.sc
           |val message = "Hello World!"
           |println(message)
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/Main.worksheet.sc")
      _ = assertNoDiff(
        server.workspaceDefinitions,
        """|/a/src/main/scala/Main.worksheet.sc
           |val message/*L0*/ = "Hello World!"
           |println/*Predef.scala*/(message/*L0*/)
           |""".stripMargin
      )
    } yield ()
  }

  test("no-position") {
    for {
      _ <- server.initialize(
        s"""
           |/metals.json
           |{"a": {"scalaVersion": "$scalaVersion"}}
           |/a/src/main/scala/Main.worksheet.sc
           |type Structural = {
           |  def foo(): Int
           |}
           |class Foo { def foo(): Int = 42 }
           |new Foo().asInstanceOf[Structural].foo()
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/Main.worksheet.sc")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        getExpected(
          """|a/src/main/scala/Main.worksheet.sc:1:1: warning: there was one feature warning; re-run with -feature for details
             |type Structural = {
             |^
             |""".stripMargin,
          compat = Map(
            "2.13.2" ->
              """|a/src/main/scala/Main.worksheet.sc:1:1: warning: 1 feature warning; re-run with -feature for details
                 |type Structural = {
                 |^
                 |""".stripMargin
          ),
          scalaVersion
        )
      )
    } yield ()
  }

  test("fatal-exception") {
    for {
      _ <- server.initialize(
        s"""
           |/metals.json
           |{"a": {"scalaVersion": "$scalaVersion"}}
           |/a/src/main/scala/StackOverflowError.worksheet.sc
           |throw new StackOverflowError()
           |/a/src/main/scala/NoSuchMethodError.worksheet.sc
           |throw new NoSuchMethodError()
           |/a/src/main/scala/IncompatibleClassChangeError.worksheet.sc
           |throw new IncompatibleClassChangeError()
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/StackOverflowError.worksheet.sc")
      _ <- server.didOpen(
        "a/src/main/scala/IncompatibleClassChangeError.worksheet.sc"
      )
      _ <- server.didOpen("a/src/main/scala/NoSuchMethodError.worksheet.sc")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/scala/IncompatibleClassChangeError.worksheet.sc:1:1: error: java.lang.IncompatibleClassChangeError
           |	at repl.MdocSession$App.<init>(IncompatibleClassChangeError.worksheet.sc:8)
           |	at repl.MdocSession$.app(IncompatibleClassChangeError.worksheet.sc:3)
           |
           |throw new IncompatibleClassChangeError()
           |^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
           |a/src/main/scala/NoSuchMethodError.worksheet.sc:1:1: error: java.lang.NoSuchMethodError
           |	at repl.MdocSession$App.<init>(NoSuchMethodError.worksheet.sc:8)
           |	at repl.MdocSession$.app(NoSuchMethodError.worksheet.sc:3)
           |
           |throw new NoSuchMethodError()
           |^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
           |a/src/main/scala/StackOverflowError.worksheet.sc:1:1: error: java.lang.StackOverflowError
           |	at repl.MdocSession$App.<init>(StackOverflowError.worksheet.sc:8)
           |	at repl.MdocSession$.app(StackOverflowError.worksheet.sc:3)
           |
           |throw new StackOverflowError()
           |^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
           |""".stripMargin
      )
    } yield ()
  }

}
