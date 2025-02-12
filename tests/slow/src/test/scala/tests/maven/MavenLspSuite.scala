package tests.maven

import java.nio.charset.StandardCharsets

import scala.meta.internal.builds.MavenBuildTool
import scala.meta.internal.builds.MavenDigest
import scala.meta.internal.io.InputStreamIO
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.Messages._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.DebugSessionParamsDataKind
import ch.epfl.scala.bsp4j.ScalaMainClass
import tests.BaseImportSuite
import tests.maven.MavenLspSuite.defaultPom

class MavenLspSuite extends BaseImportSuite("maven-import") {

  def buildTool: MavenBuildTool = MavenBuildTool(() => userConfig, workspace)

  override def currentDigest(
      workspace: AbsolutePath
  ): Option[String] = MavenDigest.current(workspace)

  test("basic") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/pom.xml
            |$defaultPom
            |/src/main/scala/A.scala
            |
            |""".stripMargin
      )
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        importBuildMessage,
      )
      _ = client.messageRequests.clear() // restart
      _ = assertStatus(_.isInstalled)
      _ <- server.didChange("pom.xml")(_ =>
        defaultPom
          .replace("<!--URL-->", "<!--Your comment--><!--MARKER-->")
      )
      _ = assertNoDiff(client.workspaceMessageRequests, "")
      _ <- server.didSave("pom.xml")
      // Comment changes do not trigger "re-import project" request
      _ = assertNoDiff(client.workspaceMessageRequests, "")
      _ <- server.didChange("pom.xml") { _ =>
        defaultPom.replace("<!--URL-->", "http://www.example.com")
      }
      _ = assertNoDiff(client.workspaceMessageRequests, "")
      _ = client.importBuildChanges = ImportBuildChanges.yes
      _ <- server.didSave("pom.xml")
    } yield {
      assertNoDiff(
        client.workspaceMessageRequests,
        importBuildChangesMessage,
      )
    }
  }

  test("force-command") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/pom.xml
            |$defaultPom
            |/src/main/scala/A.scala
            |
            |""".stripMargin
      )
      _ <- server.server.buildServerPromise.future
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        importBuildMessage,
      )
      _ = client.progressParams.clear() // restart
      _ <- server.executeCommand(ServerCommands.ImportBuild)
      _ = assertNoDiff(
        client.beginProgressMessages,
        List(
          progressMessage,
          Messages.importingBuild,
          Messages.indexing,
        ).mkString("\n"),
      )
    } yield ()
  }

  test("run") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/pom.xml
            |$defaultPom
            |/src/main/scala/a/Main.scala
            |package a
            |object Main {
            |  def main(args: Array[String]) = {
            |    val foo = sys.props.getOrElse("property", "")
            |    val bar = args(0)
            |    print(foo + bar)
            |    System.exit(0)
            |  }
            |}
            |""".stripMargin
      )
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        importBuildMessage,
      )
      debugger <- server.startDebugging(
        "maven-test-repo",
        DebugSessionParamsDataKind.SCALA_MAIN_CLASS,
        new ScalaMainClass(
          "a.Main",
          List("Bar").asJava,
          List("-Dproperty=Foo").asJava,
        ),
      )
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assertNoDiff(output, "FooBar")
  }

  test("new-dependency") {
    cleanWorkspace()
    for {
      _ <- initialize(
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
      _ = client.importBuildChanges = ImportBuildChanges.yes
      _ <- server.didChange("pom.xml") { text =>
        text.replace(
          "<!--DEPENDENCY-->",
          s"""
             |<dependency>
             |  <groupId>com.lihaoyi</groupId>
             |  <artifactId>sourcecode_2.12</artifactId>
             |  <version>0.1.4</version>
             |</dependency>
             |""".stripMargin,
        )
      }
      _ <- server.didSave("pom.xml")
      _ <-
        server
          .didChange("src/main/scala/reload/Main.scala") { text =>
            text.replaceAll("\"", "")
          }
          .recover { case e => scribe.error("compile", e) }
      _ <-
        server
          .didSave("src/main/scala/reload/Main.scala")
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
         |""".stripMargin,
    )
    for {
      _ <- initialize(
        s"""|/pom.xml
            |$badPom
            |""".stripMargin,
        expectError = true,
      )
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        importBuildMessage,
      )
      _ = assertNoDiff(
        client.workspaceShowMessages,
        ImportProjectFailed.getMessage,
      )
      _ = assertStatus(!_.isInstalled)
      _ = client.messageRequests.clear()
      _ <- server.didChange("pom.xml")(_ => defaultPom)
      _ <- server.didSave("pom.xml")
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        importBuildMessage,
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
      _ <- initialize(
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
          |src/main/scala/warning/Warning.scala:1:25: error: Unused import
          |import scala.concurrent.Future // unused
          |                        ^^^^^^
        """.stripMargin,
      )
      // we should still have references despite fatal warning
      refs <- server.workspaceReferences()
      _ = assertNoDiff(
        refs.references.map(_.symbol).sorted.mkString("\n"),
        """|_empty_/A.
           |_empty_/A.B.
           |_empty_/Warning.
           |""".stripMargin,
      )
    } yield ()
  }

}

object MavenLspSuite {
  val defaultPom: String = new String(
    InputStreamIO.readBytes(
      this.getClass.getResourceAsStream("/test-pom.xml")
    ),
    StandardCharsets.UTF_8,
  ).replace("<<>>", V.scala213)
}
