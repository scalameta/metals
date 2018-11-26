package tests

object DiagnosticsSlowSuite extends BaseSlowSuite("diagnostics") {

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
        """
          |a/src/main/scala/a/Example.scala:2:29: warning: Unused import
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
        exampleDiagnostics + mainDiagnostics
      )
      _ <- server.didOpen("b/src/main/scala/a/MainSuite.scala")
      testDiagnostics = {
        """
          |b/src/main/scala/a/MainSuite.scala:2:29: warning: Unused import
          |import java.util.concurrent.Future // unused
          |                            ^^^^^^
          |b/src/main/scala/a/MainSuite.scala:3:19: warning: Unused import
          |import scala.util.Failure // unused
          |                  ^^^^^^^
        """.stripMargin
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
}
