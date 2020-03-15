package tests.sbt

import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.util.concurrent.TimeUnit
import tests.BaseImportSuite
import scala.concurrent.Future
import scala.meta.internal.builds.SbtBuildTool
import scala.meta.internal.builds.SbtDigest
import scala.meta.internal.metals.ClientCommands
import scala.meta.internal.metals.Messages._
import scala.meta.internal.metals.MetalsSlowTaskResult
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.io.AbsolutePath

class SbtLspSuite extends BaseImportSuite("sbt-import") {

  val sbtVersion = "1.3.7"
  val buildTool: SbtBuildTool = SbtBuildTool("", () => userConfig, serverConfig)

  override def currentDigest(
      workspace: AbsolutePath
  ): Option[String] = SbtDigest.current(workspace)

  test("basic") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        s"""|/project/build.properties
            |sbt.version=$sbtVersion
            |/build.sbt
            |scalaVersion := "2.12.10"
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
      _ <- server.didChange("build.sbt")(_ + "\n// comment")
      _ = assertNoDiff(client.workspaceMessageRequests, "")
      _ <- server.didSave("build.sbt")(identity)
      // Comment changes do not trigger "re-import project" request
      _ = assertNoDiff(client.workspaceMessageRequests, "")
      _ <- server.didChange("build.sbt") { text =>
        text + "\nversion := \"1.0.0\"\n"
      }
      _ = assertNoDiff(client.workspaceMessageRequests, "")
      _ <- server.didSave("build.sbt")(identity)
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
        s"""|/project/build.properties
            |sbt.version=$sbtVersion
            |/build.sbt
            |scalaVersion := "2.12.10"
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

  test("new-dependency") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        s"""|/project/build.properties
            |sbt.version=$sbtVersion
            |/build.sbt
            |scalaVersion := "2.12.10"
            |/src/main/scala/reload/Main.scala
            |package reload
            |object Main extends App {
            |  println("sourcecode.Line(42)")
            |}
            |""".stripMargin
      )
      _ <- server.didOpen("src/main/scala/reload/Main.scala")
      _ = assertNoDiff(client.workspaceDiagnostics, "")
      _ <- server.didSave("build.sbt") { text =>
        s"""$text
           |libraryDependencies += "com.lihaoyi" %% "sourcecode" % "0.1.4"
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

  test("cancel") {
    client.slowTaskHandler = params => {
      if (params == bloopInstallProgress("sbt")) {
        Thread.sleep(TimeUnit.SECONDS.toMillis(2))
        Some(MetalsSlowTaskResult(cancel = true))
      } else {
        None
      }
    }
    cleanWorkspace()
    for {
      _ <- server.initialize(
        s"""
           |/project/build.properties
           |sbt.version=$sbtVersion
           |/build.sbt
           |version := "1.0"
           |scalaVersion := "2.12.10"
           |""".stripMargin,
        expectError = true
      )
      _ = assertStatus(!_.isInstalled)
      _ = client.slowTaskHandler = _ => None
      _ <- server.didSave("build.sbt")(_ + "\n// comment")
      _ = assertNoDiff(client.workspaceShowMessages, "")
      _ = assertStatus(!_.isInstalled)
      _ <- server.didSave("build.sbt")(_ => "version := \"1.1\" ")
      _ = assertNoDiff(client.workspaceShowMessages, "")
      _ = assertStatus(_.isInstalled)
    } yield ()
  }

  test("error") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        s"""|/project/build.properties
            |sbt.version=$sbtVersion
            |/build.sbt
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
      _ <- server.didSave("build.sbt") { _ => """scalaVersion := "2.12.10" """ }
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

  test("supported-scala") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        s"""
           |/project/build.properties
           |sbt.version=$sbtVersion
           |/build.sbt
           |scalaVersion := "2.12.10"
           |lazy val a = project.settings(scalaVersion := "2.12.4")
           |lazy val b = project.settings(scalaVersion := "2.12.3")
           |lazy val c = project.settings(scalaVersion := "2.11.12")
           |lazy val d = project.settings(scalaVersion := "2.11.8")
           |lazy val e = project.settings(scalaVersion := "2.10.7")
           |lazy val f = project.settings(scalaVersion := "${V.scala212}")
           |lazy val g = project.settings(scalaVersion := "${V.scala213}")
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
        CheckDoctor.multipleMisconfiguredProjects(8)
      )
      sourceJars <- server.buildTargetSourceJars("a")
      _ = assert(sourceJars.nonEmpty) // source jars should not be empty
      _ <- Future.sequence(
        ('a' to 'f')
          .map(project => s"$project/src/main/scala/a/A.scala")
          .map(file => server.didOpen(file))
      )
      _ = assertNoDiff(client.workspaceDiagnostics, "")
      _ = {
        val expected =
          ClientCommands.ReloadDoctor.id :: ClientCommands.RunDoctor.id :: Nil
        val actual = client.workspaceClientCommands
        assert(actual.startsWith(expected))
        client.showMessages.clear()
        client.clientCommands.clear()
      }
      _ <- server.didSave("build.sbt")(_ => """scalaVersion := "2.12.10" """)
      _ = {
        val expected = ClientCommands.ReloadDoctor.id :: Nil
        val actual = client.workspaceClientCommands
        assert(actual.startsWith(expected))
        assertNoDiff(
          client.workspaceShowMessages,
          CheckDoctor.problemsFixed.getMessage
        )
      }
    } yield ()
  }

  test("sbtopts") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        s"""
           |/project/build.properties
           |sbt.version=$sbtVersion
           |/build.sbt
           |scalaVersion := "2.12.10"
           |libraryDependencies +=
           |  // dependency won't resolve without the `bintray:scalacenter/releases` resolver
           |  // that is defined in the `custom-repositories` file.
           |  "ch.epfl.scala" %% "bloop-config" % "1.0.0-RC1+4-c5e24b66"
           |/.sbtopts
           |-Dsbt.repository.config=custom-repositories
           |/custom-repositories
           |[repositories]
           |  local
           |  maven: https://repo1.maven.org/maven2/
           |  scalacenter-releases: https://dl.bintray.com/scalacenter/releases
           |""".stripMargin
      )
      _ = assertStatus(_.isInstalled)
    } yield ()
  }

  test("jvmopts") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        s"""
           |/project/build.properties
           |sbt.version=$sbtVersion
           |/build.sbt
           |scalaVersion := "2.12.10"
           |/.jvmopts
           |-Xms1536M
           |-Xmx1536M
           |-Xss6M
           |""".stripMargin
      )
      // assert that a `.jvmopts` file doesn't break "Import build"
      _ = assertStatus(_.isInstalled)
    } yield ()
  }

  test("fatal-warnings") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        s"""
           |/project/build.properties
           |sbt.version=$sbtVersion
           |/build.sbt
           |scalaVersion := "2.12.10"
           |scalacOptions ++= List(
           |  "-Xfatal-warnings",
           |  "-Ywarn-unused"
           |)
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

  test("sbt-script") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        s"""
           |/project/build.properties
           |sbt.version=$sbtVersion
           |/build.sbt
           |scalaVersion := "2.12.10"
           |""".stripMargin,
        expectError = true,
        preInitialized = () => {
          val doesNotExist = workspace.resolve("does-not-exist")
          val config = new JsonObject
          config.add("sbt-script", new JsonPrimitive(doesNotExist.toString()))
          server.didChangeConfiguration(config.toString)
        }
      )
      _ = assertStatus(!_.isInstalled)
    } yield ()
  }

  test("sbt-version") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        """|/project/build.properties
           |sbt.version=0.13.15
           |""".stripMargin,
        expectError = true
      )
      _ = assertNoDiff(
        client.workspaceShowMessages,
        IncompatibleBuildToolVersion
          .params(SbtBuildTool("0.13.15", () => userConfig, serverConfig))
          .getMessage
      )
    } yield ()
  }
}
