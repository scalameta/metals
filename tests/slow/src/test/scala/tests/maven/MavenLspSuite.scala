package tests.maven

import java.nio.charset.StandardCharsets

import scala.meta.internal.builds.MavenBuildTool
import scala.meta.internal.builds.MavenDigest
import scala.meta.internal.io.InputStreamIO
import scala.meta.internal.metals.Messages._
import scala.meta.internal.metals.ServerCommands
import scala.meta.io.AbsolutePath

import tests.BaseImportSuite

class MavenLspSuite extends BaseImportSuite("maven-import") {

  val buildTool: MavenBuildTool = MavenBuildTool(() => userConfig)

  val defaultPom = new String(
    InputStreamIO.readBytes(
      this.getClass.getResourceAsStream("/test-pom.xml")
    ),
    StandardCharsets.UTF_8
  )

  override def currentDigest(
      workspace: AbsolutePath
  ): Option[String] = MavenDigest.current(workspace)

  test("basic") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        s"""|/pom.xml
            |$defaultPom
            |""".stripMargin
      )
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        List(
          importBuildMessage,
          progressMessage
        ).mkString("\n")
      )
      _ = client.messageRequests.clear() // restart
      _ = assertStatus(_.isInstalled)
      _ <- server.didChange("pom.xml")(_ =>
        defaultPom
          .replace("<!--URL-->", "<!--Your comment--><!--MARKER-->")
      )
      _ = assertNoDiff(client.workspaceMessageRequests, "")
      _ <- server.didSave("pom.xml")(identity)
      // Comment changes do not trigger "re-import project" request
      _ = assertNoDiff(client.workspaceMessageRequests, "")
      _ <- server.didChange("pom.xml") { _ =>
        defaultPom.replace("<!--URL-->", "http://www.example.com")
      }
      _ = assertNoDiff(client.workspaceMessageRequests, "")
      _ <- server.didSave("pom.xml")(identity)
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

  test("force-command") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        s"""|/pom.xml
            |$defaultPom
            |""".stripMargin
      )
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        List(
          importBuildMessage,
          progressMessage
        ).mkString("\n")
      )
      _ = client.messageRequests.clear() // restart
      _ <- server.executeCommand(ServerCommands.ImportBuild.id)
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        List(
          progressMessage
        ).mkString("\n")
      )
    } yield ()
  }

  test("new-dependency") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        s"""|/pom.xml
            |$defaultPom
            |/src/main/scala/reload/Main.scala
            |package reload
            |object Main extends App {
            |  println("sourcecode.Line(42)")
            |}
            |""".stripMargin
      )
      _ <- server.didOpen("src/main/scala/reload/Main.scala")
      _ = assertNoDiff(client.workspaceDiagnostics, "")
      _ <- server.didSave("pom.xml") { text =>
        text.replace(
          "<!--DEPENDENCY-->",
          s"""
             |<dependency>
             |  <groupId>com.lihaoyi</groupId>
             |  <artifactId>sourcecode_2.12</artifactId>
             |  <version>0.1.4</version>
             |</dependency>
             |""".stripMargin
        )
      }
      _ <-
        server
          .didSave("src/main/scala/reload/Main.scala") { text =>
            text.replaceAll("\"", "")
          }
          .recover { case e => scribe.error("compile", e) }
      _ = assertNoDiff(client.workspaceDiagnostics, "")
    } yield ()
  }

  test("error".flaky) {
    cleanWorkspace()
    val badPom = defaultPom.replace(
      "<!--DEPENDENCY-->",
      s"""
         |<dependency>
         |  <groupId>non.existent</groupId>
         |  <artifactId>bad</artifactId>
         |  <version>0.1.4</version>
         |</dependency>
         |""".stripMargin
    )
    for {
      _ <- server.initialize(
        s"""|/pom.xml
            |$badPom
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
      _ <- server.didSave("pom.xml")(_ => defaultPom)
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

  val scalacArgs: String =
    """|<arg>-Xfatal-warnings</arg>
       |<arg>-Ywarn-unused</arg>
       |""".stripMargin

  test("fatal-warnings") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        s"""
           |/pom.xml
           |${defaultPom.replace("<!--CONFIGURATION-->", scalacArgs)}
           |/src/main/scala/warning/Warning.scala
           |import scala.concurrent.Future // unused
           |object Warning
           |object A{
           |  object B
           |}
           |""".stripMargin
      )
      _ = assertStatus(_.isInstalled)
      _ <- server.didOpen("src/main/scala/warning/Warning.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """
          |src/main/scala/warning/Warning.scala:1:1: error: Unused import
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
