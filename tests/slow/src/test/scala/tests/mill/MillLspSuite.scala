package tests.mill

import scala.meta.internal.builds.MillBuildTool
import scala.meta.internal.builds.MillDigest
import scala.meta.internal.metals.Messages._
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.io.AbsolutePath

import tests.BaseImportSuite

/** Tests that Mill can be used with bloop */
class MillLspSuite extends BaseImportSuite("mill-import") {

  def buildTool: MillBuildTool = MillBuildTool(() => userConfig, workspace)

  override def currentDigest(
      workspace: AbsolutePath
  ): Option[String] = MillDigest.current(workspace)

  test("basic") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/build.sc
           |import mill._, scalalib._
           |object foo extends ScalaModule {
           |  def scalaVersion = "${V.scala213}"
           |}
           |/.mill-version
           |0.12.14
        """.stripMargin
      )
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        importBuildMessage,
      )
      _ = client.messageRequests.clear() // restart
      _ = assertStatus(_.isInstalled)
      _ <- server.didChange("build.sc")(_ + "\n// comment")
      _ = assertNoDiff(client.workspaceMessageRequests, "")
      _ <- server.didSave("build.sc")
      // Comment changes do not trigger "re-import project" request
      _ = assertNoDiff(client.workspaceMessageRequests, "")
      _ <- server.didChange("build.sc") { text =>
        text +
          s"""|
              |object bar extends ScalaModule {
              |  def scalaVersion = "${V.scala213}"
              |}
              |""".stripMargin
      }
      _ = assertNoDiff(client.workspaceMessageRequests, "")
      _ = client.importBuildChanges = ImportBuildChanges.yes
      _ <- server.didSave("build.sc")
    } yield {
      assertNoDiff(
        client.workspaceMessageRequests,
        importBuildChangesMessage,
      )
    }
  }

  test("new-dependency") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/build.sc
           |import mill._, scalalib._
           |object foo extends ScalaModule {
           |  def scalaVersion = "${V.scala213}"
           |  /*DEPS*/
           |}
           |/foo/src/reload/Main.scala
           |package reload
           |object Main extends App {
           |  println("sourcecode.Line(42)")
           |}
           |/.mill-version
           |0.12.14
           |""".stripMargin
      )
      _ <- server.didOpen("foo/src/reload/Main.scala")
      _ = assertNoDiff(client.workspaceDiagnostics, "")
      _ = client.importBuildChanges = ImportBuildChanges.yes
      _ <- server.didChange("build.sc") { text =>
        text.replace(
          "/*DEPS*/",
          "def ivyDeps = Agg(ivy\"com.lihaoyi::sourcecode::0.1.9\")",
        )
      }
      _ <- server.didSave("build.sc")
      _ <-
        server
          .didChange("foo/src/reload/Main.scala") { text =>
            text.replaceAll("\"", "")
          }
          .recover { case e => scribe.error("compile", e) }
      _ <- server.didSave("foo/src/reload/Main.scala")
      _ = assertNoDiff(client.workspaceDiagnostics, "")
    } yield ()
  }

  test("error") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/.mill-version
           |0.12.14
           |/build.sc
           |, syntax error
           |/.mill-version
           |0.12.11
           |""".stripMargin,
        expectError = true,
      )
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        s"""|$importBuildMessage
            |${ImportProjectFailedSuggestBspSwitch.params().getMessage}
            |""".stripMargin,
      )
      _ = assertStatus(!_.isInstalled)
      _ = client.messageRequests.clear()
      _ <- server.didChange("build.sc") { _ =>
        s"""
           |import mill._, scalalib._
           |object foo extends ScalaModule {
           |  def scalaVersion = "${V.scala213}"
           |}
        """.stripMargin
      }
      _ <- server.didSave("build.sc")
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        importBuildMessage,
      )
      _ = assertStatus(_.isInstalled)
    } yield ()
  }

  test("fatal-warnings") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/.mill-version
           |0.12.14
           |/build.sc
           |import mill._, scalalib._
           |object foo extends ScalaModule {
           |  def scalaVersion = "${V.scala213}"
           |  def scalacOptions = Seq("-Xfatal-warnings", "-Ywarn-unused")
           |}
           |/foo/src/Warning.scala
           |import scala.concurrent.Future // unused
           |object Warning
           |object A{
           |  object B
           |}
           |/.mill-version
           |0.12.11
           |""".stripMargin
      )
      _ = assertStatus(_.isInstalled)
      _ <- server.didOpen("foo/src/Warning.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """
          |foo/src/Warning.scala:1:25: error: Unused import
          |import scala.concurrent.Future // unused
          |                        ^^^^^^
        """.stripMargin,
      )
      // we should still have references despite fatal warning
      refs <- server.workspaceReferences()
      _ = assertNoDiff(
        refs.references
          .withFilter(_.location.getUri().endsWith("Warning.scala"))
          .map(_.symbol)
          .sorted
          .mkString("\n"),
        """|_empty_/A.
           |_empty_/A.B.
           |_empty_/Warning.
           |""".stripMargin,
      )
    } yield ()
  }
}
