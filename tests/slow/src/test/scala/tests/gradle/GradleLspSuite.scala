package tests.gradle

import scala.concurrent.Future
import scala.meta.internal.builds.GradleDigest
import scala.meta.internal.metals.ClientCommands
import scala.meta.internal.metals.Messages._
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.io.AbsolutePath
import scala.meta.internal.builds.GradleBuildTool
import tests.BaseImportSuite

class GradleLspSuite extends BaseImportSuite("gradle-import") {

  val buildTool: GradleBuildTool = GradleBuildTool(() => userConfig)

  override def currentDigest(
      workspace: AbsolutePath
  ): Option[String] = GradleDigest.current(workspace)

  testAsync("basic") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        """|/build.gradle
           |plugins {
           |    id 'scala'
           |}
           |repositories {
           |    mavenCentral()
           |}
           |dependencies {
           |    implementation 'org.scala-lang:scala-library:2.12.10'
           |}
           |""".stripMargin
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
      _ <- server.didChange("build.gradle")(_ + "\n// comment")
      _ = assertNoDiff(client.workspaceMessageRequests, "")
      _ <- server.didSave("build.gradle")(identity)
      // Comment changes do not trigger "re-import project" request
      _ = assertNoDiff(client.workspaceMessageRequests, "")
      _ <- server.didChange("build.gradle") { text =>
        text + "\ndef version = \"1.0.0\"\n"
      }
      _ = assertNoDiff(client.workspaceMessageRequests, "")
      _ <- server.didSave("build.gradle")(identity)
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

  testAsync("transitive") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        """|/build.gradle
           |plugins {
           |    id 'scala'
           |}
           |repositories {
           |    mavenCentral()
           |}
           |dependencies {
           |    implementation 'org.scala-lang:scala-reflect:2.12.10'
           |}
           |""".stripMargin
      )
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        List(
          // Project has no .bloop directory so user is asked to "import via bloop"
          importBuildMessage,
          progressMessage
        ).mkString("\n")
      )
      _ = client.messageRequests.clear()
      _ = assertStatus(_.isInstalled)
    } yield assertNoDiff(client.workspaceMessageRequests, "")
  }

  testAsync("force-command") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        """|/build.gradle
           |plugins {
           |    id 'scala'
           |}
           |repositories {
           |    mavenCentral()
           |}
           |dependencies {
           |    implementation 'org.scala-lang:scala-library:2.12.10'
           |}
           |""".stripMargin
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
      _ <- server.executeCommand(ServerCommands.ImportBuild.id)
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        List(
          progressMessage
        ).mkString("\n")
      )
    } yield ()
  }

  testAsync("new-dependency") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        """|/build.gradle
           |plugins {
           |    id 'scala'
           |}
           |repositories {
           |    mavenCentral()
           |}
           |dependencies {
           |    implementation 'org.scala-lang:scala-library:2.12.10'
           |}
           |/src/main/scala/reload/Main.scala
           |package reload
           |object Main extends App {
           |  println("sourcecode.Line(42)")
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("src/main/scala/reload/Main.scala")
      _ = assertNoDiff(client.workspaceDiagnostics, "")
      _ <- server.didSave("build.gradle") { text =>
        s"""$text
           |dependencies {
           |    implementation 'com.lihaoyi:sourcecode_2.12:0.1.4'
           |}
           |""".stripMargin
      }
      _ <- server
        .didSave("src/main/scala/reload/Main.scala") { text =>
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
        """|/build.gradle
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
      _ <- server.didSave("build.gradle") { _ =>
        """|plugins {
           |    id 'scala'
           |}
           |repositories {
           |    mavenCentral()
           |}
           |dependencies {
           |    implementation 'org.scala-lang:scala-library:2.12.10'
           |}
           |""".stripMargin
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

  private def projectWithVersion(version: String) = {
    s"""|plugins {
        |    id 'scala'
        |}
        |repositories {
        |    mavenCentral()
        |}
        |dependencies {
        |    implementation 'org.scala-lang:scala-library:$version'
        |}
        |""".stripMargin
  }

  testAsync("different-scala") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        s"""
           |/build.gradle
           |${projectWithVersion("2.12.7")}
           |/a/build.gradle
           |${projectWithVersion("2.12.4")}
           |/b/build.gradle
           |${projectWithVersion("2.12.3")}
           |/c/build.gradle
           |${projectWithVersion("2.11.12")}
           |/d/build.gradle
           |${projectWithVersion("2.11.8")}
           |/e/build.gradle
           |${projectWithVersion("2.10.7")}
           |/f/build.gradle
           |${projectWithVersion(V.scala212)}
           |/g/build.gradle
           |${projectWithVersion(V.scala213)}
           |/settings.gradle
           |include 'a'
           |include 'b'
           |include 'c'
           |include 'd'
           |include 'e'
           |include 'f'
           |include 'g'
           |/a/src/main/scala/a/A.scala
           |package a
           |object A // 2.12.4
           |/b/src/main/scala/a/A.scala
           |package a // 2.12.3
           |object A
           |/c/src/main/scala/a/A.scala
           |package a
           |object A // 2.11.12
           |/d/src/main/scala/a/A.scala
           |package a
           |object A // 2.11.8
           |/e/src/main/scala/a/A.scala
           |package a
           |object A // 2.10.7
           |/f/src/main/scala/a/A.scala
           |package a
           |object A // ${V.scala212}
           |/g/src/main/scala/a/A.scala
           |package a
           |object A // ${V.scala213}
           |""".stripMargin,
        expectError = true
      )
      _ = assertStatus(_.isInstalled)
      _ = assertNoDiff(
        client.messageRequests.peekLast(),
        // only main projects since no empty test targets are created for gradle
        CheckDoctor.multipleMisconfiguredProjects(5)
      )
      _ <- Future.sequence(
        ('a' to 'f')
          .map(project => s"$project/src/main/scala/a/A.scala")
          .map(file => server.didOpen(file))
      )
      _ = assertNoDiff(client.workspaceDiagnostics, "")
      _ = {
        val expected = ClientCommands.ReloadDoctor.id :: ClientCommands.RunDoctor.id :: Nil
        val actual = client.workspaceClientCommands
        assert(actual.startsWith(expected))
      }
    } yield ()
  }

  testAsync("fatal-warnings") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        """
          |/build.gradle
          |plugins {
          |    id 'scala'
          |}
          |repositories {
          |    mavenCentral()
          |}
          |dependencies {
          |    implementation 'org.scala-lang:scala-library:2.12.10'
          |}
          |tasks.withType(ScalaCompile) {
          |    scalaCompileOptions.additionalParameters = [
          |         '-Xfatal-warnings',
          |         '-Ywarn-unused'
          |    ]
          |}
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
