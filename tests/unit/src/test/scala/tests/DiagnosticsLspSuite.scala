package tests

import java.nio.file.Files

class DiagnosticsLspSuite extends BaseLspSuite("diagnostics") {

  test("diagnostics") {
    cleanCompileCache("a")
    cleanCompileCache("b")
    for {
      _ <- initialize(
        """|
           |/metals.json
           |{
           |  "a": {
           |     "scalacOptions": [
           |       "-Ywarn-unused"
           |     ]
           |   },
           |  "b": {
           |     "scalacOptions": [
           |       "-Ywarn-unused"
           |     ]
           |   }
           |}
           |/a/src/main/scala/a/Example.scala
           |package a
           |import java.util.concurrent.Future // unused
           |import scala.util.Failure // unused
           |class Example
           |/a/src/main/scala/a/Main.scala
           |package a
           |import java.util.concurrent.Future // unused
           |import scala.util.Failure // unused
           |class Main
           |/b/src/main/scala/a/MainSuite.scala
           |package a
           |import java.util.concurrent.Future // unused
           |import scala.util.Failure // unused
           |class MainSuite
           |""".stripMargin
      )
      _ = assertNoDiff(client.workspaceDiagnostics, "")
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      exampleDiagnostics = {
        """|a/src/main/scala/a/Example.scala:2:29: warning: Unused import
           |import java.util.concurrent.Future // unused
           |                            ^^^^^^
           |a/src/main/scala/a/Example.scala:3:19: warning: Unused import
           |import scala.util.Failure // unused
           |                  ^^^^^^^
           |""".stripMargin
      }
      mainDiagnostics = {
        """|a/src/main/scala/a/Main.scala:2:29: warning: Unused import
           |import java.util.concurrent.Future // unused
           |                            ^^^^^^
           |a/src/main/scala/a/Main.scala:3:19: warning: Unused import
           |import scala.util.Failure // unused
           |                  ^^^^^^^
           |""".stripMargin
      }
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        exampleDiagnostics + mainDiagnostics,
      )
      _ <- server.didOpen("b/src/main/scala/a/MainSuite.scala")
      testDiagnostics = {
        """|b/src/main/scala/a/MainSuite.scala:2:29: warning: Unused import
           |import java.util.concurrent.Future // unused
           |                            ^^^^^^
           |b/src/main/scala/a/MainSuite.scala:3:19: warning: Unused import
           |import scala.util.Failure // unused
           |                  ^^^^^^^
           |""".stripMargin
      }
      _ = assertNoDiff(
        client.pathDiagnostics("b/src/main/scala/a/MainSuite.scala"),
        testDiagnostics,
      )
      // This seems to be currently broken on CI - diagnostics not being refreshed
      // _ <- server.didSave("b/src/main/scala/a/MainSuite.scala")(
      //   _.linesIterator.filterNot(_.startsWith("import")).mkString("\n")
      // )
      // _ = assertNoDiff(
      //   client.workspaceDiagnostics,
      //   exampleDiagnostics + mainDiagnostics
      // )
      // _ <- server.didSave("a/src/main/scala/a/Main.scala")(
      //   _.linesIterator.filterNot(_.startsWith("import")).mkString("\n")
      // )
      // _ = assertNoDiff(client.workspaceDiagnostics, exampleDiagnostics)
    } yield ()
  }

  test("reset".ignore) {
    cleanCompileCache("a")
    for {
      _ <- initialize(
        """|
           |/metals.json
           |{"a": {}}
           |/a/src/main/scala/Main.scala
           |object Main {
           |  val a = 2
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/Main.scala")
      _ <- server.didChange("a/src/main/scala/Main.scala")(
        _.replace("val a = 2", "val a = 1\n  val a = 2")
      )
      _ <- server.didSave("a/src/main/scala/Main.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        // Duplicate diagnostics are expected, the scala compiler reports them.
        """|a/src/main/scala/Main.scala:3:7: error: a is already defined as value a
           |  val a = 2
           |      ^
           |a/src/main/scala/Main.scala:3:7: error: a  is already defined as value a
           |  val a = 2
           |      ^^^^^
           |""".stripMargin,
      )
      _ <- server.didChange("a/src/main/scala/Main.scala")(
        _.replace("val a = 1\n  ", "")
      )
      _ <- server.didSave("a/src/main/scala/Main.scala")
      // FIXME: https://github.com/scalacenter/bloop/issues/785
      _ = assertNoDiff(client.workspaceDiagnostics, "")
    } yield ()
  }

  test("post-typer") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|
            |/metals.json
            |{
            |  "a": {}
            |}
            |/a/src/main/scala/a/Post.scala
            |package a
            |trait Post {
            |  def post: Int
            |}
            |object Post extends Post
            |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Post.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/scala/a/Post.scala:5:8: error: object creation impossible.
           |Missing implementation for member of trait Post:
           |  def post: Int = ???
           |
           |object Post extends Post
           |       ^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("deprecation") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|
            |/metals.json
            |{
            |  "a": {"scalacOptions": ["-deprecation", "-Xfatal-warnings"]}
            |}
            |/a/src/main/scala/a/Deprecation.scala
            |package a
            |object Deprecation {
            |  val stream = Stream.empty
            |}
            |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Deprecation.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/scala/a/Deprecation.scala:3:16: error: value Stream in package scala is deprecated (since 2.13.0): Use LazyList instead of Stream
           |  val stream = Stream.empty
           |               ^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("exponential") {
    cleanWorkspace()
    def expo(n: Int, pkg: String): String =
      s"""package $pkg
         |object Expo$n {
         | val a: Int = ""
         | val b: Int = ""
         | val c: Int = ""
         | val d: Int = ""
         | val e: Int = ""
         |}
         |""".stripMargin
    for {
      _ <- initialize(
        s"""|
            |/metals.json
            |{
            |  "a": {}
            |}
            |/a/src/main/scala/a/Expo1.scala
            |${expo(1, "a")}
            |/a/src/main/scala/a/Expo2.scala
            |${expo(2, "a")}
            |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Expo1.scala")
      _ = assertNoDiff(
        client.workspaceDiagnosticsCount,
        """
          |a/src/main/scala/a/Expo1.scala: 2
          |a/src/main/scala/a/Expo2.scala: 2
          |""".stripMargin,
      )
    } yield ()
  }

  test("reset-build") {
    cleanWorkspace()
    import scala.meta.internal.metals.ServerCommands
    for {
      _ <- initialize(
        s"""|
            |/metals.json
            |{
            |  "a": {}
            |}
            |/a/src/main/scala/a/A.scala
            |package a
            |object A {
            |  val x: Int = 42
            |}
            |/a/src/main/scala/a/B.scala
            |package a
            |object B {
            |  val x: String = 42
            |}
            |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/B.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/scala/a/B.scala:3:19: error: type mismatch;
           | found   : Int(42)
           | required: String
           |  val x: String = 42
           |                  ^^
           |""".stripMargin,
      )
      _ <- server.executeCommand(ServerCommands.DisconnectBuildServer)
      _ = assertNoDiagnostics()
      _ <- server.didChange("a/src/main/scala/a/B.scala")(
        _.replace("String", "Int")
      )
      _ <- server.didSave("a/src/main/scala/a/B.scala")
      _ <- server.didClose("a/src/main/scala/a/B.scala")
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ <- server.executeCommand(ServerCommands.ConnectBuildServer)
      _ = assertNoDiagnostics()
    } yield ()
  }

  test("delete") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|
           |/metals.json
           |{"a": {}}
           |/a/src/main/scala/a/A.scala
           |object A {
           |  val a = 2
           |}
           |/a/src/main/scala/a/B.scala
           |object B {
           |  val a: String = 2
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/scala/a/B.scala:2:19: error: type mismatch;
           | found   : Int(2)
           | required: String
           |  val a: String = 2
           |                  ^
           |""".stripMargin,
      )
      _ = Files.delete(server.toPath("a/src/main/scala/a/B.scala").toNIO)
      _ <- server.didSave("a/src/main/scala/a/A.scala")
      _ = assertNoDiagnostics()
    } yield ()
  }

  test("single-source") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """
          |/metals.json
          |{
          |  "a": {
          |    "additionalSources" : [ "a/weird/path/A.scala" ]
          |  }
          |}
          |/a/weird/path/A.scala
          |object A {
          |  val n: Int = ""
          |}
        """.stripMargin
      )
      _ <- server.didOpen("a/weird/path/A.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/weird/path/A.scala:2:16: error: type mismatch;
           | found   : String("")
           | required: Int
           |  val n: Int = ""
           |               ^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("duplicated-source") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """
          |/metals.json
          |{
          |  "a": {},
          |  "b": { "additionalSources": ["a/src/main/scala/"], "dependsOn": ["a"]}
          |}
          |/a/src/main/scala/a/A.scala
          |object A extends App{
          |  val n: Int = ""
          |}
        """.stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ <- server.didSave("a/src/main/scala/a/A.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/scala/a/A.scala:2:16: error: type mismatch;
           | found   : String("")
           | required: Int
           |  val n: Int = ""
           |               ^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("tokenization-error") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """
          |/metals.json
          |{
          |  "a": {}
          |}
          |/a/src/main/scala/a/A.scala
          |object A {
          |  val n: Int = ""
          |}
        """.stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/scala/a/A.scala:2:16: error: type mismatch;
           | found   : String("")
           | required: Int
           |  val n: Int = ""
           |               ^^
           |""".stripMargin,
      )
      _ <- server.didChange("a/src/main/scala/a/A.scala")(
        _.replace("val n: Int = \"\"", "val n: Int = \" ")
      )
      _ <- server.didSave("a/src/main/scala/a/A.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/scala/a/A.scala:2:16: error: unclosed string literal
           |  val n: Int = " 
           |               ^
           |""".stripMargin,
      )
    } yield ()
  }

  test("errors-in-dependency") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {},
           |  "b": { "dependsOn": [ "a" ] }
           |}
           |/${A.path}
           |${A.content("Int", "1")}
           |/${B.path}
           |${B.content("Int", "1")}
        """.stripMargin
      )
      _ <- server.didOpen(B.path)
      _ = assertNoDiagnostics()
      _ <- server.didChange(B.path) { _ => B.content("String", "1") }
      _ <- server.didSave(B.path)
      _ = assertNoDiff(
        client.pathDiagnostics(B.path),
        """|b/src/main/scala/b/B.scala:4:19: error: type mismatch;
           | found   : Int(1)
           | required: String
           |  val b: String = 1
           |                  ^
           |b/src/main/scala/b/B.scala:5:20: error: type mismatch;
           | found   : Int
           | required: String
           |  val a1: String = a.A.a
           |                   ^^^^^
           |""".stripMargin,
      )
      _ <- server.didOpen(A.path)
      _ <- server.didChange(A.path) { _ => A.content("String", "1") }
      _ <- server.didSave(A.path)
      _ = assertNoDiff(
        client.pathDiagnostics(A.path),
        """|a/src/main/scala/a/A.scala:4:19: error: type mismatch;
           | found   : Int(1)
           | required: String
           |  val a: String = 1
           |                  ^
           |""".stripMargin,
      )
      // we want the diagnostics for B to disappear
      _ = assertNoDiff(client.pathDiagnostics(B.path), "")

      _ <- server.didChange(A.path) { _ => A.content("String", "\"aa\"") }
      _ <- server.didSave(A.path)
      _ = assertNoDiff(client.pathDiagnostics(A.path), "")

      // we want the diagnostics for B to appear
      _ = assertNoDiff(
        client.pathDiagnostics(B.path),
        """|b/src/main/scala/b/B.scala:4:19: error: type mismatch;
           | found   : Int(1)
           | required: String
           |  val b: String = 1
           |                  ^
           |""".stripMargin,
      )

      _ <- server.didOpen(B.path)
      _ <- server.didChange(B.path) { _ => B.content("String", "\"aa\"") }
      _ <- server.didSave(B.path)
      _ = assertNoDiagnostics()

    } yield ()
  }

  test("errors-in-dependency-2") {
    cleanWorkspace()
    val C = new Basic("c")
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {},
           |  "b": { "dependsOn": [ "a" ] },
           |  "c": { "dependsOn": [ "a" ] }
           |}
           |/${A.path}
           |${A.content("Int", "1")}
           |/${B.path}
           |${B.content("Int", "1")}
           |/${C.path}
           |${C.content("Int", "1")}
        """.stripMargin
      )

      _ <- server.didOpen(B.path)
      _ = assertNoDiagnostics()
      _ <- server.didChange(B.path) { _ => B.content("Int", "\"aa\"") }
      _ <- server.didSave(B.path)

      _ <- server.didOpen(A.path)
      _ <- server.didChange(A.path) { _ => A.content("Int", "\"aa\"") }
      _ <- server.didSave(A.path)
      _ <- server.didChange(A.path) { _ => A.content("Int", "1") }
      _ <- server.didSave(A.path)

      _ <- server.didOpen(B.path)
      _ <- server.didChange(B.path) { _ => B.content("Int", "1") }
      _ <- server.didSave(B.path)

      _ <- server.didOpen(C.path)
      _ <- server.didChange(C.path) { _ => C.content("Int", "\"aa\"") }
      _ <- server.didSave(C.path)

      _ <- server.didOpen(A.path)
      _ <- server.didChange(A.path) { _ => A.content("Int", "\"aa\"") }
      _ <- server.didSave(A.path)
      _ <- server.didChange(A.path) { _ => A.content("Int", "1") }
      _ <- server.didSave(A.path)

      _ <- server.didOpen(C.path)
      _ <- server.didChange(C.path) { _ => C.content("Int", "1") }
      _ <- server.didSave(C.path)

      _ = assertNoDiagnostics()

      _ <- server.didOpen(B.path)
      _ <- server.didChange(B.path) { _ => B.content("Int", "\"aa\"") }
      _ <- server.didSave(B.path)

      _ = assertNoDiff(
        client.pathDiagnostics(B.path),
        """|b/src/main/scala/b/B.scala:4:16: error: type mismatch;
           | found   : String("aa")
           | required: Int
           |  val b: Int = "aa"
           |               ^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  object A extends Basic("a")
  object B {
    val path = "b/src/main/scala/b/B.scala"
    def content(tpe: String, value: String): String =
      s"""|package b
          |
          |object B {
          |  val b: $tpe = $value
          |  val a1: $tpe = a.A.a
          |}
          |""".stripMargin
  }

  class Basic(name: String) {
    val path: String = s"$name/src/main/scala/$name/${name.toUpperCase()}.scala"
    def content(tpe: String, value: String): String =
      s"""|package $name
          |
          |object ${name.toUpperCase()} {
          |  val $name: $tpe = $value
          |}
          |""".stripMargin
  }
}
