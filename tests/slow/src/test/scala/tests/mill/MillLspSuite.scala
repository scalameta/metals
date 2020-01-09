package tests.mill

import scala.meta.io.AbsolutePath
import scala.meta.internal.builds.MillDigest
import scala.meta.internal.metals.Messages._
import scala.meta.internal.builds.MillBuildTool
import tests.BaseImportSuite

class MillLspSuite extends BaseImportSuite("mill-import") {

  val buildTool: MillBuildTool = MillBuildTool(() => userConfig)

  override def currentDigest(
      workspace: AbsolutePath
  ): Option[String] = MillDigest.current(workspace)

  testAsync("basic") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        """
          |/build.sc
          |import mill._, scalalib._
          |object foo extends ScalaModule {
          |  def scalaVersion = "2.12.10"
          |}
        """.stripMargin
      )
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        List(
          // Project has no .bloop directory so user is asked to "import via bloop"
          importBuildMessage,
          progressMessage
        ).mkString("\n")
      )
      _ = client.messageRequests.clear() // restart
      _ = assertStatus(_.isInstalled)
      _ <- server.didChange("build.sc")(_ + "\n// comment")
      _ = assertNoDiff(client.workspaceMessageRequests, "")
      _ <- server.didSave("build.sc")(identity)
      // Comment changes do not trigger "re-import project" request
      _ = assertNoDiff(client.workspaceMessageRequests, "")
      _ <- server.didChange("build.sc") { text =>
        text +
          """|
             |object bar extends ScalaModule {
             |  def scalaVersion = "2.12.10"
             |}
             |""".stripMargin
      }
      _ = assertNoDiff(client.workspaceMessageRequests, "")
      _ <- server.didSave("build.sc")(identity)
    } yield {
      assertNoDiff(
        client.workspaceMessageRequests,
        List(
          // Project has .bloop directory so user is asked to "re-import project"
          importBuildChangesMessage,
          progressMessage
        ).mkString("\n")
      )
    }
  }

  testAsync("new-dependency") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        """
          |/build.sc
          |import mill._, scalalib._
          |object foo extends ScalaModule {
          |  def scalaVersion = "2.12.10"
          |  /*DEPS*/
          |}
          |/foo/src/reload/Main.scala
          |package reload
          |object Main extends App {
          |  println("sourcecode.Line(42)")
          |}
          |""".stripMargin
      )
      _ <- server.didOpen("foo/src/reload/Main.scala")
      _ = assertNoDiff(client.workspaceDiagnostics, "")
      _ <- server.didSave("build.sc") { text =>
        text.replace(
          "/*DEPS*/",
          "def ivyDeps = Agg(ivy\"com.lihaoyi::sourcecode::0.1.4\")"
        )
      }
      _ <- server
        .didSave("foo/src/reload/Main.scala") { text =>
          text.replaceAll("\"", "")
        }
        .recover { case e => scribe.error("compile", e) }
      _ = assertNoDiff(client.workspaceDiagnostics, "")
    } yield ()
  }

  testAsync("error") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        """|/build.sc
           |, syntax error
           |""".stripMargin,
        expectError = true
      )
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        List(
          importBuildMessage,
          progressMessage
        ).mkString("\n")
      )
      _ = assertNoDiff(
        client.workspaceShowMessages,
        ImportProjectFailed.getMessage
      )
      _ = assertStatus(!_.isInstalled)
      _ = client.messageRequests.clear()
      _ <- server.didSave("build.sc") { _ =>
        """
          |import mill._, scalalib._
          |object foo extends ScalaModule {
          |  def scalaVersion = "2.12.10"
          |}
        """.stripMargin,
      }
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        List(
          importBuildMessage,
          progressMessage
        ).mkString("\n")
      )
      _ = assertStatus(_.isInstalled)
    } yield ()
  }

  testAsync("fatal-warnings") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        """
          |/build.sc
          |import mill._, scalalib._
          |object foo extends ScalaModule {
          |  def scalaVersion = "2.12.10"
          |  def scalacOptions = Seq("-Xfatal-warnings", "-Ywarn-unused")
          |}
          |/foo/src/Warning.scala
          |import scala.concurrent.Future // unused
          |object Warning
          |object A{
          |  object B
          |}
          |""".stripMargin
      )
      _ = assertStatus(_.isInstalled)
      _ <- server.didOpen("foo/src/Warning.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """
          |foo/src/Warning.scala:1:1: error: Unused import
          |import scala.concurrent.Future // unused
          |^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
        """.stripMargin
      )
      // we should still have references despite fatal warning
      _ = assertNoDiff(
        server.workspaceReferences().references.map(_.symbol).mkString("\n"),
        """|_empty_/A.
           |_empty_/A.B.
           |_empty_/Warning.
           |""".stripMargin
      )
    } yield ()
  }
}
