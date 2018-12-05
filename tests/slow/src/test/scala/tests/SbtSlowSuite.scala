package tests

import java.util.concurrent.TimeUnit
import scala.concurrent.Future
import scala.meta.internal.metals.ClientCommands
import scala.meta.internal.metals.Messages._
import scala.meta.internal.metals.MetalsSlowTaskResult
import scala.meta.internal.metals.SbtDigest
import scala.meta.internal.metals.ServerCommands

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
          ImportBuildViaBloop.params.getMessage,
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
          ReimportSbtProject.params.getMessage,
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
          ImportBuildViaBloop.params.getMessage,
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
          ImportBuildViaBloop.params.getMessage,
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
          ImportBuildViaBloop.params.getMessage,
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
        """
          |/project/build.properties
          |sbt.version=1.2.6
          |/build.sbt
          |scalaVersion := "2.12.7"
          |lazy val a = project.settings(scalaVersion := "2.12.4")
          |lazy val b = project.settings(scalaVersion := "2.12.3")
          |lazy val c = project.settings(scalaVersion := "2.11.12")
          |lazy val d = project.settings(scalaVersion := "2.11.8")
          |lazy val e = project.settings(scalaVersion := "2.10.7")
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
          |""".stripMargin,
        expectError = true
      )
      _ = assertStatus(_.isInstalled)
      _ = assertNoDiff(
        client.messageRequests.peekLast(),
        CheckDoctor.multipleMisconfiguredProjects(6)
      )
      _ <- Future.sequence(
        ('a' to 'e')
          .map(project => s"$project/src/main/scala/a/A.scala")
          .map(file => server.didOpen(file))
      )
      _ = assertNoDiff(client.workspaceDiagnostics, "")
      _ = assertNoDiff(
        server.workspaceDefinitions,
        """
          |/a/src/main/scala/a/A.scala
          |package a
          |object A/*L1*/ // 2.12.4
          |/b/src/main/scala/a/A.scala
          |package a/*<no symbol>*/ // 2.12.3
          |object A/*<no symbol>*/
          |/c/src/main/scala/a/A.scala
          |package a
          |object A/*L1*/ // 2.11.12
          |/d/src/main/scala/a/A.scala
          |package a/*<no symbol>*/
          |object A/*<no symbol>*/ // 2.11.8
          |/e/src/main/scala/a/A.scala
          |package a/*<no symbol>*/
          |object A/*<no symbol>*/ // 2.10.7
          |""".stripMargin
      )
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

}
