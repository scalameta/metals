package tests

import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.util.concurrent.TimeUnit
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.meta.internal.metals.ClientCommands
import scala.meta.internal.metals.Messages._
import scala.meta.internal.metals.MetalsSlowTaskResult
import scala.meta.internal.metals.SbtDigest
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.internal.metals.BuildTool.Sbt

object SbtSlowSuite extends BaseSlowSuite("import") {

  def currentChecksum(): String =
    SbtDigest.current(workspace).getOrElse {
      fail("no sbt checksum for workspace")
    }
  def assertNoStatus(): Unit =
    server.server.tables.sbtDigests.getStatus(currentChecksum()) match {
      case Some(value) =>
        fail(s"expected no status. obtained $value", stackBump = 1)
      case None =>
        () // OK
    }
  def assertStatus(fn: SbtDigest.Status => Boolean): Unit = {
    val checksum = currentChecksum()
    server.server.tables.sbtDigests.getStatus(checksum) match {
      case Some(status) =>
        assert(fn(status))
      case None =>
        fail(s"missing persisted checksum $checksum", stackBump = 1)
    }
  }

  override def testAsync(
      name: String,
      maxDuration: Duration
  )(run: => Future[Unit]): Unit = {
    if (isWindows) {
      // Skip SbtSlowSuite on Windows because they're flaky due to likely the small
      // available memory on Appveyor CI machines.
      ignore(name)(())
    } else {
      super.testAsync(name, maxDuration)(run)
    }
  }

  testAsync("basic") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        """|/project/build.properties
           |sbt.version=1.2.6
           |/build.sbt
           |scalaVersion := "2.12.7"
           |""".stripMargin
      )
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        List(
          // Project has no .bloop directory so user is asked to "import via bloop"
          ImportBuild.params.getMessage,
          BloopInstallProgress.message
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
          ImportBuildChanges.params.getMessage,
          BloopInstallProgress.message
        ).mkString("\n")
      )
    }
  }

  testAsync("force-command") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        """|/project/build.properties
           |sbt.version=1.2.6
           |/build.sbt
           |scalaVersion := "2.12.7"
           |""".stripMargin
      )
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        List(
          // Project has no .bloop directory so user is asked to "import via bloop"
          ImportBuild.params.getMessage,
          BloopInstallProgress.message
        ).mkString("\n")
      )
      _ = client.messageRequests.clear() // restart
      _ <- server.executeCommand(ServerCommands.ImportBuild.id)
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        List(
          BloopInstallProgress.message
        ).mkString("\n")
      )
    } yield ()
  }

  testAsync("new-dependency") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        """|/project/build.properties
           |sbt.version=1.2.6
           |/build.sbt
           |scalaVersion := "2.12.7"
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

  testAsync("cancel") {
    client.slowTaskHandler = params => {
      if (params == BloopInstallProgress) {
        Thread.sleep(TimeUnit.SECONDS.toMillis(2))
        Some(MetalsSlowTaskResult(cancel = true))
      } else {
        None
      }
    }
    cleanWorkspace()
    for {
      _ <- server.initialize(
        """
          |/project/build.properties
          |sbt.version=1.2.6
          |/build.sbt
          |version := "1.0"
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

  testAsync("error") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        """|/project/build.properties
           |sbt.version=1.2.6
           |/build.sbt
           |, syntax error
           |""".stripMargin,
        expectError = true
      )
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        List(
          ImportBuild.params.getMessage,
          BloopInstallProgress.message
        ).mkString("\n")
      )
      _ = assertNoDiff(
        client.workspaceShowMessages,
        ImportProjectFailed.getMessage
      )
      _ = assertStatus(!_.isInstalled)
      _ = client.messageRequests.clear()
      _ <- server.didSave("build.sbt") { _ =>
        """scalaVersion := "2.12.7" """
      }
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        List(
          ImportBuild.params.getMessage,
          BloopInstallProgress.message
        ).mkString("\n")
      )
      _ = assertStatus(_.isInstalled)
    } yield ()
  }

  testAsync("supported-scala") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        s"""
           |/project/build.properties
           |sbt.version=1.2.6
           |/build.sbt
           |scalaVersion := "2.12.7"
           |lazy val a = project.settings(scalaVersion := "2.12.4")
           |lazy val b = project.settings(scalaVersion := "2.12.3")
           |lazy val c = project.settings(scalaVersion := "2.11.12")
           |lazy val d = project.settings(scalaVersion := "2.11.8")
           |lazy val e = project.settings(scalaVersion := "2.10.7")
           |lazy val f = project.settings(scalaVersion := "${V.scala212}")
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
           |""".stripMargin,
        expectError = true
      )
      _ = assertStatus(_.isInstalled)
      _ = assertNoDiff(
        client.messageRequests.peekLast(),
        CheckDoctor.multipleMisconfiguredProjects(6)
      )
      _ <- Future.sequence(
        ('a' to 'f')
          .map(project => s"$project/src/main/scala/a/A.scala")
          .map(file => server.didOpen(file))
      )
      _ = assertNoDiff(client.workspaceDiagnostics, "")
      _ = {
        assertNoDiff(
          client.workspaceClientCommands,
          List(
            ClientCommands.ReloadDoctor.id,
            ClientCommands.RunDoctor.id
          ).mkString("\n")
        )
        client.showMessages.clear()
        client.clientCommands.clear()
      }
      _ <- server.didSave("build.sbt")(_ => """scalaVersion := "2.12.7" """)
      _ = {
        assertNoDiff(
          client.workspaceClientCommands,
          ClientCommands.ReloadDoctor.id
        )
        assertNoDiff(
          client.workspaceShowMessages,
          CheckDoctor.problemsFixed.getMessage
        )
      }
    } yield ()
  }

  testAsync("sbtopts") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        """
          |/project/build.properties
          |sbt.version=1.2.6
          |/build.sbt
          |scalaVersion := "2.12.7"
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

  testAsync("jvmopts") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        """
          |/project/build.properties
          |sbt.version=1.2.6
          |/build.sbt
          |scalaVersion := "2.12.7"
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

  testAsync("fatal-warnings") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        """
          |/project/build.properties
          |sbt.version=1.2.6
          |/build.sbt
          |scalaVersion := "2.12.7"
          |scalacOptions ++= List(
          |  "-Xfatal-warnings",
          |  "-Ywarn-unused"
          |)
          |/src/main/scala/warning/Warning.scala
          |import scala.concurrent.Future // unused
          |object Warning
          |""".stripMargin
      )
      _ = assertStatus(_.isInstalled)
      _ <- server.didOpen("src/main/scala/warning/Warning.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """
          |src/main/scala/warning/Warning.scala:1:1: warning: Unused import
          |import scala.concurrent.Future // unused
          |^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
        """.stripMargin
      )
    } yield ()
  }

  testAsync("sbt-script") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        """
          |/project/build.properties
          |sbt.version=1.2.6
          |/build.sbt
          |scalaVersion := "2.12.7"
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

  testAsync("sbt-version") {
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
        IncompatibleSbtVersion.params(Sbt("0.13.15")).getMessage
      )
    } yield ()
  }
}
