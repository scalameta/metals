package tests

import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.StatisticsConfig

object DiagnosticsSlowSuite extends BaseSlowSuite("diagnostics") {

  override def serverConfig: MetalsServerConfig = super.serverConfig.copy(
    statistics = StatisticsConfig("diagnostics-clear")
  )

  testAsync("diagnostics") {
    cleanCompileCache("a")
    cleanCompileCache("b")
    for {
      _ <- server.initialize(
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
        """|a/src/main/scala/a/Example.scala:2:1: warning: Unused import
           |import java.util.concurrent.Future // unused
           |^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
           |a/src/main/scala/a/Example.scala:3:1: warning: Unused import
           |import scala.util.Failure // unused
           |^^^^^^^^^^^^^^^^^^^^^^^^^
           |""".stripMargin
      }
      mainDiagnostics = {
        """|a/src/main/scala/a/Main.scala:2:1: warning: Unused import
           |import java.util.concurrent.Future // unused
           |^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
           |a/src/main/scala/a/Main.scala:3:1: warning: Unused import
           |import scala.util.Failure // unused
           |^^^^^^^^^^^^^^^^^^^^^^^^^
           |""".stripMargin
      }
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        exampleDiagnostics + mainDiagnostics
      )
      _ <- server.didOpen("b/src/main/scala/a/MainSuite.scala")
      testDiagnostics = {
        """|b/src/main/scala/a/MainSuite.scala:2:1: warning: Unused import
           |import java.util.concurrent.Future // unused
           |^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
           |b/src/main/scala/a/MainSuite.scala:3:1: warning: Unused import
           |import scala.util.Failure // unused
           |^^^^^^^^^^^^^^^^^^^^^^^^^
           |""".stripMargin
      }
      _ = assertNoDiff(
        client.pathDiagnostics("b/src/main/scala/a/MainSuite.scala"),
        testDiagnostics
      )
      _ <- server.didSave("b/src/main/scala/a/MainSuite.scala")(
        _.lines.filterNot(_.startsWith("import")).mkString("\n")
      )
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        exampleDiagnostics + mainDiagnostics
      )
      _ <- server.didSave("a/src/main/scala/a/Main.scala")(
        _.lines.filterNot(_.startsWith("import")).mkString("\n")
      )
      _ = assertNoDiff(client.workspaceDiagnostics, exampleDiagnostics)
    } yield ()
  }

  ignore("reset") {
    cleanCompileCache("a")
    for {
      _ <- server.initialize(
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
      _ <- server.didSave("a/src/main/scala/Main.scala")(
        _.replaceAllLiterally("val a = 2", "val a = 1\n  val a = 2")
      )
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        // Duplicate diagnostics are expected, the scala compiler reports them.
        """|a/src/main/scala/Main.scala:3:7: error: a is already defined as value a
           |  val a = 2
           |      ^
           |a/src/main/scala/Main.scala:3:7: error: a  is already defined as value a
           |  val a = 2
           |      ^^^^^
           |""".stripMargin
      )
      _ <- server.didSave("a/src/main/scala/Main.scala")(
        _.replaceAllLiterally("val a = 1\n  ", "")
      )
      // FIXME: https://github.com/scalacenter/bloop/issues/785
      _ = assertNoDiff(client.workspaceDiagnostics, "")
    } yield ()
  }

  testAsync("post-typer") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
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
        """|a/src/main/scala/a/Post.scala:5:1: error: object creation impossible, since method post in trait Post of type => Int is not defined
           |object Post extends Post
           |^^^^^^^^^^^^^^^^^^^^^^^^
           |""".stripMargin
      )
    } yield ()
  }

  testAsync("deprecation") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        s"""|
            |/metals.json
            |{
            |  "a": {"scalacOptions": ["-deprecation", "-Xfatal-warnings"]}
            |}
            |/a/src/main/scala/a/Deprecation.scala
            |package a
            |object Deprecation {
            | val x = readInt()
            |}
            |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Deprecation.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/scala/a/Deprecation.scala:3:10: error: method readInt in trait DeprecatedPredef is deprecated (since 2.11.0): use the method in `scala.io.StdIn`
           | val x = readInt()
           |         ^^^^^^^
           |""".stripMargin
      )
    } yield ()
  }

  testAsync("exponential") {
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
      _ <- server.initialize(
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
          |""".stripMargin
      )
    } yield ()
  }

}
