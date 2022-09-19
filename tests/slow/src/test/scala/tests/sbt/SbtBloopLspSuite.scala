package tests.sbt

import java.util.concurrent.TimeUnit

import scala.concurrent.Future

import scala.meta.internal.builds.SbtBuildTool
import scala.meta.internal.builds.SbtDigest
import scala.meta.internal.io.FileIO
import scala.meta.internal.metals.ClientCommands
import scala.meta.internal.metals.Messages._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.clients.language.MetalsSlowTaskResult
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.io.AbsolutePath

import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import tests.BaseImportSuite
import tests.ScriptsAssertions

class SbtBloopLspSuite
    extends BaseImportSuite("sbt-bloop-import")
    with ScriptsAssertions {

  val sbtVersion = V.sbtVersion
  val scalaVersion = V.scala213
  val buildTool: SbtBuildTool = SbtBuildTool(None, () => userConfig)

  override def currentDigest(
      workspace: AbsolutePath
  ): Option[String] = SbtDigest.current(workspace)

  test("basic") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/project/build.properties
            |sbt.version=$sbtVersion
            |/build.sbt
            |scalaVersion := "${V.scala213}"
            |""".stripMargin
      )
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        List(
          // Project has no .bloop directory so user is asked to "import via bloop"
          importBuildMessage,
          progressMessage,
        ).mkString("\n"),
      )
      _ = client.messageRequests.clear() // restart
      _ = assertStatus(_.isInstalled)
      _ <- server.didChange("build.sbt")(_ + "\n// comment")
      _ = assertNoDiff(client.workspaceMessageRequests, "")
      _ <- server.didSave("build.sbt")(identity)
      // Comment changes do not trigger "re-import project" request
      _ = assertNoDiff(client.workspaceMessageRequests, "")
      _ = client.importBuildChanges = ImportBuildChanges.yes
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
          progressMessage,
        ).mkString("\n"),
      )
    }
  }

  test("no-sbt-version") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/build.sbt
            |scalaVersion := "${V.scala213}"
            |""".stripMargin
      )
      _ = assertStatus(_.isInstalled)
      projectVersion = workspace.resolve("project/build.properties").readText
      _ = assertNoDiff(projectVersion, s"sbt.version=${V.sbtVersion}")
    } yield ()
  }

  test("force-command") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/project/build.properties
            |sbt.version=$sbtVersion
            |/build.sbt
            |scalaVersion := "${V.scala213}"
            |""".stripMargin
      )
      _ <- server.server.buildServerPromise.future
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        List(
          // Project has no .bloop directory so user is asked to "import via bloop"
          importBuildMessage,
          progressMessage,
        ).mkString("\n"),
      )
      _ = client.messageRequests.clear() // restart
      _ <- server.executeCommand(ServerCommands.ImportBuild)
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        List(
          progressMessage
        ).mkString("\n"),
      )
    } yield ()
  }
  test("force-command-multiple") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/project/build.properties
            |sbt.version=$sbtVersion
            |/build.sbt
            |scalaVersion := "${V.scala213}"
            |""".stripMargin
      )
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        List(
          // Project has no .bloop directory so user is asked to "import via bloop"
          importBuildMessage,
          progressMessage,
        ).mkString("\n"),
      )
      _ = client.messageRequests.clear() // restart
      _ <- server
        .executeCommand(ServerCommands.ImportBuild)
        .zip(server.executeCommand(ServerCommands.ImportBuild))
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        List(
          progressMessage
        ).mkString("\n"),
      )
      _ = assertNoDiff(
        client.workspaceShowMessages,
        List(
          ImportAlreadyRunning.getMessage()
        ).mkString("\n"),
      )

    } yield ()
  }

  test("new-dependency") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/project/build.properties
            |sbt.version=$sbtVersion
            |/build.sbt
            |scalaVersion := "${V.scala213}"
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
      _ <- server.didSave("build.sbt") { text =>
        s"""$text
           |libraryDependencies += "com.lihaoyi" %% "sourcecode" % "0.1.4"
           |""".stripMargin
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
      _ <- initialize(
        s"""
           |/project/build.properties
           |sbt.version=$sbtVersion
           |/build.sbt
           |version := "1.0"
           |scalaVersion := "${V.scala213}"
           |""".stripMargin,
        expectError = true,
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
      _ <- initialize(
        s"""|/project/build.properties
            |sbt.version=$sbtVersion
            |/build.sbt
            |, syntax error
            |""".stripMargin,
        expectError = true,
      )
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        List(
          importBuildMessage,
          progressMessage,
        ).mkString("\n"),
      )
      _ = assertNoDiff(
        client.workspaceShowMessages,
        ImportProjectFailed.getMessage,
      )
      _ = assertStatus(!_.isInstalled)
      _ = client.messageRequests.clear()
      _ <- server.didSave("build.sbt") { _ =>
        s"""scalaVersion := "${V.scala213}" """
      }
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        List(
          importBuildMessage,
          progressMessage,
        ).mkString("\n"),
      )
      _ = assertStatus(_.isInstalled)
    } yield ()
  }

  test("supported-scala") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/project/build.properties
           |sbt.version=$sbtVersion
           |/build.sbt
           |scalaVersion := "${V.scala213}"
           |lazy val a = project.settings(scalaVersion := "2.12.4")
           |lazy val b = project.settings(scalaVersion := "2.12.3")
           |lazy val c = project.settings(scalaVersion := "2.11.11")
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
           |object A // 2.11.11
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
        expectError = true,
      )
      _ = assertStatus(_.isInstalled)
      _ = assertNoDiff(
        client.messageRequests.peekLast(),
        UnsupportedScalaVersion.message(
          Set("2.12.4", "2.12.3", "2.11.8", "2.11.11", "2.10.7")
        ),
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
      _ = client.importBuildChanges = ImportBuildChanges.yes
      _ <- server.didSave("build.sbt")(_ =>
        s"""scalaVersion := "${V.scala213}" """
      )
      _ = {
        val expected = ClientCommands.ReloadDoctor.id :: Nil
        val actual = client.workspaceClientCommands
        assert(actual.startsWith(expected))
        assertNoDiff(
          client.workspaceShowMessages,
          CheckDoctor.problemsFixed.getMessage,
        )
      }
    } yield ()
  }

  test("sbtopts") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/project/build.properties
           |sbt.version=$sbtVersion
           |/build.sbt
           |scalaVersion := "${V.scala213}"
           |/.sbtopts
           |-J-Xlog:gc:gc_log
           |""".stripMargin
      )
      _ = assertStatus(_.isInstalled)
      // assert that jvm created gc log file
      // that means that sbtopts were passed correctly
      jvmLog = FileIO.listFiles(workspace).find(_.filename == "gc_log")
      _ = assert(jvmLog.isDefined)
    } yield ()
  }

  test("jvmopts") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/project/build.properties
           |sbt.version=$sbtVersion
           |/build.sbt
           |scalaVersion := "${V.scala213}"
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
      _ <- initialize(
        s"""
           |/project/build.properties
           |sbt.version=$sbtVersion
           |/build.sbt
           |scalaVersion := "${V.scala213}"
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
        """.stripMargin,
      )
      // we should still have references despite fatal warning
      _ = assertNoDiff(
        server.workspaceReferences().references.map(_.symbol).mkString("\n"),
        """|_empty_/A.
           |_empty_/A.B.
           |_empty_/Warning.
           |""".stripMargin,
      )
    } yield ()
  }

  test("sbt-script") {
    cleanWorkspace()
    writeLayout(
      s"""
         |/project/build.properties
         |sbt.version=$sbtVersion
         |/build.sbt
         |scalaVersion := "${V.scala213}"
         |""".stripMargin
    )
    for {
      _ <- server.initialize()
      _ <- Future {
        val doesNotExist = workspace.resolve("does-not-exist")
        val config = new JsonObject
        config.add("sbt-script", new JsonPrimitive(doesNotExist.toString()))
        server.didChangeConfiguration(config.toString)
      }
      _ <- server.initialized()
    } yield {
      assertStatus(!_.isInstalled)
    }
  }

  test("sbt-version") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/project/build.properties
           |sbt.version=0.13.15
           |""".stripMargin,
        expectError = true,
      )
      _ = assertNoDiff(
        client.workspaceShowMessages,
        IncompatibleBuildToolVersion
          .params(SbtBuildTool(Some("0.13.15"), () => userConfig))
          .getMessage,
      )
    } yield ()
  }

  test("min-sbt-version") {
    val minimal =
      SbtBuildTool(None, () => UserConfiguration.default).minimumVersion
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/project/build.properties
            |sbt.version=$minimal
            |""".stripMargin
      )
      _ = assertStatus(_.isInstalled)
    } yield ()
  }

  test("definition-deps") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/project/build.properties
            |sbt.version=${V.sbtVersion}
            |
            |/build.sbt
            |scalaVersion := "$scalaVersion"
         """.stripMargin
      )
      _ <- assertDefinitionAtLocation(
        "build.sbt",
        "sc@@alaVersion := \"2.12.11\"",
        "sbt/Keys.scala",
        expectedLine = 190,
      )
    } yield ()
  }

  test("definition-meta") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/project/plugins.sbt
            |addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.9.21")
            |
            |/build.sbt
            |scalaVersion := "$scalaVersion"
         """.stripMargin
      )
      _ <- assertDefinitionAtLocation(
        "project/plugins.sbt",
        "addSbt@@Plugin(\"ch.epfl.scala\" % \"sbt-scalafix\" % \"0.9.19\")",
        "sbt/Defaults.scala",
      )
    } yield ()
  }

  test("definition-local") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/build.sbt
           |scalaVersion := "$scalaVersion"
           |val hello = "Hello"
           |
           |
           |val bye = hello
         """.stripMargin
      )
      _ <- assertDefinitionAtLocation(
        "build.sbt",
        "val bye = hel@@lo",
        "build.sbt",
        1,
      )
    } yield ()
  }

  test("sbt-file-hover") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/build.sbt
            |scalaVersion := "$scalaVersion"
         """.stripMargin
      )
      hoverRes <- assertHoverAtPos("build.sbt", 0, 2)
      expectedHoverRes =
        """|```scala
           |val scalaVersion: SettingKey[String]
           |```
           |```range
           |scalaVersion
           |```""".stripMargin
      _ = assertNoDiff(hoverRes, expectedHoverRes)
    } yield ()

  }

  test("scala-file-hover") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/build.sbt
            |scalaVersion := "$scalaVersion"
            |/project/Deps.scala
            |import sbt._
            |import Keys._
            |object Deps {
            |  val scalatest = "org.scalatest" %% "scalatest" % "3.2.4"
            |}
         """.stripMargin
      )
      hoverRes <- assertHoverAtPos("project/Deps.scala", 3, 9)
      expectedHoverRes =
        """|```scala
           |val scalatest: ModuleID
           |```
           |```range
           |val scalatest = "org.scalatest" %% "scalatest" % "3.2.4"
           |```
           |""".stripMargin
      _ = assertNoDiff(hoverRes, expectedHoverRes)
    } yield ()

  }

  test("sbt-file-autocomplete") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/build.sbt
            |scalaVersion := "$scalaVersion"
            |libraryDependencies ++= Seq()
         """.stripMargin
      )
      completionList <- server.completion("build.sbt", "libraryDependencies@@")
      expectedCompletionList = "libraryDependencies: SettingKey[Seq[ModuleID]]"
      _ = assertNoDiff(completionList, expectedCompletionList)
    } yield ()

  }

  test("sbt-meta-scala-source-basics") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/build.sbt
            |scalaVersion := MetaValues.scalaVersion
            |/project/MetaValues.scala
            |import scala.util.Success
            |object MetaValues {
            |  val scalaVersion = "$scalaVersion"
            |}
         """.stripMargin
      )
      _ <- server.didOpen("project/MetaValues.scala")
      _ = assertNoDiff(
        server.workspaceDefinitions,
        s"""|/project/MetaValues.scala
            |import scala.util.Success/*Try.scala*/
            |object MetaValues/*L1*/ {
            |  val scalaVersion/*L2*/ = "$scalaVersion"
            |}
            |""".stripMargin,
      )
    } yield ()

  }

  test("sbt-meta-symbols") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/build.sbt
            |scalaVersion := MetaValues.scalaVersion
            |/project/MetaValues.scala
            |import scala.util.Success
            |object MetaValues {
            |  val scalaVersion = "$scalaVersion"
            |}
         """.stripMargin
      )
      _ <- server.didOpen("build.sbt")
      _ = server.workspaceDefinitions
      _ = assertNoDiff(
        server.workspaceDefinitions,
        """|/build.sbt
           |scalaVersion/*Keys.scala*/ :=/*Structure.scala*/ MetaValues/*MetaValues.scala:1*/.scalaVersion/*MetaValues.scala:2*/
           |""".stripMargin,
      )
    } yield ()
  }

  test("sbt-references") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/build.sbt
            |def foo(): String = "2.13.2"
            |def bar(): String = foo() 
            |scalaVersion := "2.13.2"
         """.stripMargin
      )
      references <- server.references("build.sbt", "foo")
      _ = assertNoDiff(
        references,
        """|build.sbt:1:5: info: reference
           |def foo(): String = "2.13.2"
           |    ^^^
           |build.sbt:2:21: info: reference
           |def bar(): String = foo() 
           |                    ^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("sbt-rename") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/build.sbt
            |def foo(): String = "2.13.2"
            |def bar(): String = foo() 
            |scalaVersion := "2.13.2"
         """.stripMargin
      )
      _ <- server.assertRename(
        "build.sbt",
        s"""|def foo(): String = "2.13.2"
            |def bar(): String = foo@@() 
            |scalaVersion := "2.13.2"
         """.stripMargin,
        Map(
          "build.sbt" ->
            s"""|def foo2(): String = "2.13.2"
                |def bar(): String = foo2() 
                |scalaVersion := "2.13.2"
          """.stripMargin
        ),
        Set("build.sbt"),
        "foo2",
      )
    } yield ()
  }
}
