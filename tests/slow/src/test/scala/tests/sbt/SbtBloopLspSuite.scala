package tests.sbt

import java.util.concurrent.TimeUnit

import scala.concurrent.Future

import scala.meta.internal.builds.SbtBuildTool
import scala.meta.internal.builds.SbtDigest
import scala.meta.internal.io.FileIO
import scala.meta.internal.metals.ClientCommands
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.Messages._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.io.AbsolutePath

import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import org.eclipse.lsp4j.MessageActionItem
import tests.BaseImportSuite
import tests.JavaHomeChangeTest
import tests.ScriptsAssertions

class SbtBloopLspSuite
    extends BaseImportSuite("sbt-bloop-import")
    with ScriptsAssertions
    with JavaHomeChangeTest {

  override def serverConfig: MetalsServerConfig =
    super.serverConfig.copy(askToRestartBloop = true)

  val sbtVersion = V.sbtVersion
  val scalaVersion = V.scala213
  val buildTool: SbtBuildTool = SbtBuildTool(None, workspace, () => userConfig)

  override def currentDigest(
      workspace: AbsolutePath
  ): Option[String] = SbtDigest.current(workspace)

  test("environment-variables") {
    cleanWorkspace()

    val fakeShell = workspace.resolve("fake-shell.sh")
    fakeShell.writeText("""
                          |export MY_ENV_VAR="test-value"
                          |exec bash "$@"
                          |""".stripMargin)
    fakeShell.toFile.setExecutable(true)

    for {
      // should fail with NoSuchElementException
      _ <- initialize(
        s"""|/project/build.properties
            |sbt.version=$sbtVersion
            |/build.sbt
            |name := sys.env("MY_ENV_VAR")
            |""".stripMargin,
        expectError = true,
      ).recover {
        case t: java.util.NoSuchElementException =>
          assert(t.getMessage.contains("key not found: MY_ENV_VAR"))
        case t => fail(s"Expected NoSuchElementException but got $t")
      }

      _ <- server.didChangeConfiguration(
        s"""{
           |  "defaultShell": "$fakeShell"
           |}""".stripMargin
      )

      _ <- server.executeCommand(ServerCommands.ImportBuild)

      _ = assertStatus(_.isInstalled)

    } yield ()
  }

  for (sbtVersion <- List(V.sbtVersion, V.sbt2Version)) {
    test(s"basic-$sbtVersion") {
      cleanWorkspace()
      // directory should not be used as sbt script
      workspace.resolve("sbt").createDirectories()
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
          importBuildMessage,
        )
        _ = client.messageRequests.clear() // restart
        _ = assertStatus(_.isInstalled)
        _ <- server.didChange("build.sbt")(_ + "\n// comment")
        _ = assertNoDiff(client.workspaceMessageRequests, "")
        _ <- server.didSave("build.sbt")
        // Comment changes do not trigger "re-import project" request
        _ = assertNoDiff(client.workspaceMessageRequests, "")
        _ = client.importBuildChanges = ImportBuildChanges.yes
        _ <- server.didChange("build.sbt") { text =>
          text + "\nversion := \"1.0.0\"\n"
        }
        _ = assertNoDiff(client.workspaceMessageRequests, "")
        _ <- server.didSave("build.sbt")
      } yield {
        assertNoDiff(
          client.workspaceMessageRequests,
          importBuildChangesMessage,
        )
      }
    }
  }

  test("inner") {
    cleanWorkspace()
    client.importBuild = ImportBuild.yes
    for {
      _ <- initialize(
        s"""|/inner/project/build.properties
            |sbt.version=$sbtVersion
            |/inner/build.sbt
            |scalaVersion := "${V.scala213}"
            |/inner/src/main/scala/A.scala
            |
            |object A {
            |  val i: Int = "aaa"
            |}
            |""".stripMargin
      )
      _ <- server.server.indexingPromise.future
      _ = assert(workspace.resolve("inner/.bloop").exists)
      _ = assert(server.server.bspSession.get.main.isBloop)
      _ <- server.didOpen("inner/src/main/scala/A.scala")
      _ <- server.didSave("inner/src/main/scala/A.scala")
      _ = assertNoDiff(
        client.pathDiagnostics("inner/src/main/scala/A.scala"),
        """|inner/src/main/scala/A.scala:3:16: error: type mismatch;
           | found   : String("aaa")
           | required: Int
           |  val i: Int = "aaa"
           |               ^^^^^
           |""".stripMargin,
      )
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
        importBuildMessage,
      )
      _ = client.progressParams.clear() // restart
      _ <- server
        .executeCommand(ServerCommands.ImportBuild)
        .zip(server.executeCommand(ServerCommands.ImportBuild))
      _ = assertNoDiff(
        client.beginProgressMessages,
        List(
          progressMessage,
          progressMessage,
          Messages.importingBuild,
          Messages.indexing,
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
      _ <- server.didChange("build.sbt") { text =>
        s"""$text
           |libraryDependencies += "com.lihaoyi" %% "sourcecode" % "0.4.4"
           |""".stripMargin
      }
      _ <- server.didSave("build.sbt")
      _ <-
        server
          .didChange("src/main/scala/reload/Main.scala") { text =>
            text.replaceAll("\"", "")
          }
          .recover { case e => scribe.error("compile", e) }
      _ <- server.didSave("src/main/scala/reload/Main.scala")
      _ = assertNoDiff(client.workspaceDiagnostics, "")
    } yield ()
  }

  test("cancel") {
    cleanWorkspace()
    client.onWorkDoneProgressStart = (name, cancelParams) => {
      if (name == progressMessage) {
        Thread.sleep(TimeUnit.SECONDS.toMillis(2))
        server.fullServer.didCancelWorkDoneProgress(cancelParams)
      }
    }
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
      _ = client.onWorkDoneProgressStart = (_, _) => {}
      _ <- server.didChange("build.sbt")(_ + "\n// comment")
      _ <- server.didSave("build.sbt")
      _ = assertNoDiff(client.workspaceShowMessages, "")
      _ = assertStatus(!_.isInstalled)
      _ <- server.didChange("build.sbt")(_ => "version := \"1.1\" ")
      _ <- server.didSave("build.sbt")
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
        s"""|$importBuildMessage
            |${ImportProjectFailedSuggestBspSwitch.params().getMessage}
            |""".stripMargin,
      )
      _ = assertStatus(!_.isInstalled)
      _ = client.messageRequests.clear()
      _ <- server.didChange("build.sbt") { _ =>
        s"""scalaVersion := "${V.scala213}" """
      }
      _ <- server.didSave("build.sbt")
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        importBuildMessage,
      )
      _ = assertStatus(_.isInstalled)
    } yield ()
  }

  test("produce-diagnostics-on-error") {
    cleanWorkspace()
    workspace.resolve("sbt").createDirectories()
    for {
      _ <- initialize(
        s"""|/project/build.properties
            |sbt.version=$sbtVersion
            |/build.sbt
            |scalaVersion := "${V.scala213}"
            |val x: String = 42
            |""".stripMargin,
        expectError = true,
      )
      _ = assertStatus(!_.isInstalled)
      _ = assertNoDiff(
        client.pathDiagnostics("build.sbt", formatMessage = false),
        """|error: type mismatch;
           | found   : Int(42)
           | required: String
           |val x: String = 42
           |                ^
           |""".stripMargin,
      )
      _ = server.didChange("build.sbt", s"""scalaVersion := "${V.scala213}" """)
      _ = server.didSave("build.sbt")
      _ <- server.executeCommand(ServerCommands.ImportBuild)
      _ = assertStatus(_.isInstalled)
    } yield {
      assertNoDiff(client.pathDiagnostics("build.sbt"), "")
    }
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
      _ <- server.didChange("build.sbt")(_ =>
        s"""scalaVersion := "${V.scala213}" """
      )
      _ <- server.didSave("build.sbt")
      _ = {
        val expected = ClientCommands.ReloadDoctor.id
        val actual = client.workspaceClientCommands
        assert(
          actual.contains(expected),
          "ReloadDoctor should have been invoked on the client",
        )
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
           |/.jvmopts
           |-Xms1536M
           |-Xmx1536M
           |-Xss6M
           |""".stripMargin
      )
      _ = assertStatus(_.isInstalled)
      // assert that jvm created gc log file
      // that means that sbtopts were passed correctly
      jvmLog = FileIO.listFiles(workspace).find(_.filename == "gc_log")
      _ = assert(jvmLog.isDefined)
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

  test("min-sbt-version") {
    val minimal =
      SbtBuildTool(
        None,
        workspace,
        () => UserConfiguration.default,
      ).minimumVersion
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/project/build.properties
            |sbt.version=$minimal
            |/build.sbt
            |scalaVersion := "$scalaVersion"
            |""".stripMargin
      )
      _ = assertStatus(_.isInstalled)
    } yield ()
  }

  test("sbt-file-after-reset") {
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
      _ = server.headServer.tables.buildTool.reset()
      _ = assert(server.headServer.tables.buildTool.selectedBuildTool().isEmpty)
      _ <- server.executeCommand(ServerCommands.ConnectBuildServer)
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

  test("lsp-features") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/build.sbt
           |scalaVersion := "$scalaVersion"
           |val hello = "Hello"
           |libraryDependencies ++= Seq()
           |
           |val bye = hello
           |/project/build.properties
           |sbt.version=$sbtVersion
           |/project/Deps.scala
           |import sbt._
           |import Keys._
           |object Deps {
           |  val scalatest = "org.scalatest" %% "scalatest" % "3.2.16"
           |}
           |/project/MetaValues.scala
           |import scala.util.Success
           |object MetaValues {
           |  val scalaVersion = "$scalaVersion"
           |}
           |/project/plugins.sbt
           |addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.9.21")
         """.stripMargin
      )
      /* Test definition */
      _ <- assertDefinitionAtLocation(
        "build.sbt",
        "val bye = hel@@lo",
        "build.sbt",
        1,
      )
      _ <- assertDefinitionAtLocation(
        "build.sbt",
        s"sc@@alaVersion := \"$scalaVersion\"",
        "sbt/Keys.scala",
        expectedLine = 192,
      )
      _ <- assertDefinitionAtLocation(
        "project/plugins.sbt",
        "addSbt@@Plugin(\"ch.epfl.scala\" % \"sbt-scalafix\" % \"0.9.19\")",
        "sbt/Defaults.scala",
      )
      /* Test hover */
      hoverRes <- assertHoverAtPos("build.sbt", 0, 2)
      expectedHoverRes =
        """|```scala
           |val scalaVersion: SettingKey[String]
           |```
           |```range
           |scalaVersion
           |```""".stripMargin
      _ = assertNoDiff(hoverRes, expectedHoverRes)
      hoverRes <- assertHoverAtPos("project/Deps.scala", 3, 9)
      expectedHoverRes =
        """|```scala
           |val scalatest: ModuleID
           |```
           |```range
           |val scalatest = "org.scalatest" %% "scalatest" % "3.2.16"
           |```
           |""".stripMargin
      _ = assertNoDiff(hoverRes, expectedHoverRes)
      /* Test completion */
      completionList <- server.completion("build.sbt", "libraryDependencies@@")
      expectedCompletionList =
        """|libraryDependencies: SettingKey[Seq[ModuleID]]
           |scalafixLibraryDependencies: Def.Initialize[List[ModuleID]]""".stripMargin
      _ = assertNoDiff(completionList, expectedCompletionList)
      /* Test workspace definitions */
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
      _ <- server.didOpen("build.sbt")
      _ = server.workspaceDefinitions
      _ = assertNoDiff(
        server.workspaceDefinitions,
        """|/build.sbt
           |scalaVersion/*Keys.scala*/ :=/*Structure.scala*/ "2.13.18"
           |val hello/*L1*/ = "Hello"
           |libraryDependencies/*Keys.scala*/ ++=/*Structure.scala*/ Seq/*;GenericCompanion.scala;Seq.scala*/()
           |
           |val bye/*L4*/ = hello/*L1*/
           |/project/MetaValues.scala
           |import scala.util.Success/*Try.scala*/
           |object MetaValues/*L1*/ {
           |  val scalaVersion/*L2*/ = "2.13.18"
           |}
           |  
           |""".stripMargin,
      )
      /* Test references */
      references <- server.references("build.sbt", "hello")
      _ = assertNoDiff(
        references,
        """|build.sbt:2:5: info: reference
           |val hello = "Hello"
           |    ^^^^^
           |build.sbt:5:11: info: reference
           |val bye = hello
           |          ^^^^^
           |""".stripMargin,
      )
      /* Test rename */
      _ <- server.assertRename(
        "build.sbt",
        s"""|scalaVersion := "$scalaVersion"
            |val hello@@ = "Hello"
            |libraryDependencies ++= Seq()
            |
            |val bye = hello
         """.stripMargin,
        Map(
          "build.sbt" ->
            s"""|scalaVersion := "$scalaVersion"
                |val hello2 = "Hello"
                |libraryDependencies ++= Seq()
                |
                |val bye = hello2
          """.stripMargin
        ),
        Set("build.sbt"),
        "hello2",
      )
      /* Test semantic highlighting */
      _ <- server.didChangeConfiguration(
        """{
          |  "enable-semantic-highlighting": true
          |}
          |""".stripMargin
      )
      _ <- server.didOpen("build.sbt")
      _ <- server.didSave("build.sbt")
      _ <- server.assertSemanticHighlight(
        "build.sbt",
        s"""|<<scalaVersion>>/*variable,readonly*/ <<:=>>/*method*/ <<"$scalaVersion">>/*string*/
            |<<val>>/*keyword*/ <<hello>>/*variable,definition,readonly*/ = <<"Hello">>/*string*/
            |<<libraryDependencies>>/*variable,readonly*/ <<++=>>/*method*/ <<Seq>>/*class*/()
            |
            |<<val>>/*keyword*/ <<bye>>/*variable,definition,readonly*/ = <<hello>>/*variable,readonly*/
         """.stripMargin,
        s"""|scalaVersion := "$scalaVersion"
            |val hello = "Hello"
            |libraryDependencies ++= Seq()
            |
            |val bye = hello
            |""".stripMargin,
      )
      /* Test inlay hints */
      _ <- server.didChangeConfiguration(
        """|{"inlayHints": {
           |  "inferredTypes": {"enable":true},
           |  "implicitConversions": {"enable":true},
           |  "implicitArguments": {"enable":true},
           |  "typeParameters": {"enable":true},
           |  "hintsInPatternMatch": {"enable":true}
           |}}
           |""".stripMargin
      )
      _ <- server.didOpen("build.sbt")
      _ <- server.didSave("build.sbt")
      _ = assertNoDiagnostics()
      _ <- server.assertInlayHints(
        "build.sbt",
        s"""|scalaVersion := "2.13.18"
            |val hello/*: String<<java/lang/String#>>*/ = "Hello"
            |libraryDependencies ++= Seq/*[Nothing<<scala/Nothing#>>]*/()/*(appendSeq<<sbt/Append.appendSeq().>>)*/
            |
            |val bye/*: String<<java/lang/String#>>*/ = hello
            |           
           """.stripMargin,
      )
    } yield ()
  }

  checkJavaHomeUpdate(
    "bloop-java-home-update",
    fileContent => s"""|/build.sbt
                       |scalaVersion := "$scalaVersion"
                       |val a = project.in(file("a"))
                       |$fileContent
                       |""".stripMargin,
    errorMessage =
      """|a/src/main/scala/a/A.scala:2:8: error: object random is not a member of package java.util
         |import java.util.random.RandomGenerator
         |       ^^^^^^^^^^^^^^^^
         |a/src/main/scala/a/A.scala:4:13: error: not found: value RandomGenerator
         |  val gen = RandomGenerator.of("name")
         |            ^^^^^^^^^^^^^^^
         |""".stripMargin,
  )

  test("switch-build-server-while-connect") {
    cleanWorkspace()
    val layout =
      s"""|/project/build.properties
          |sbt.version=${V.sbtVersion}
          |/build.sbt
          |scalaVersion := "${V.scala213}"
          |/src/main/scala/A.scala
          |
          |object A {
          |  val i: Int = "aaa"
          |}
          |""".stripMargin
    writeLayout(layout)
    client.importBuild = ImportBuild.yes
    client.selectBspServer = { _ => new MessageActionItem("sbt") }
    for {
      _ <- server.initialize()
      _ = server.initialized()
      connectionProvider = server.headServer.connectionProvider.Connect
      _ = while (connectionProvider.getOngoingRequest().isEmpty) {
        // wait for connect to start
        Thread.sleep(100)
      }
      bloopConnectF = connectionProvider.getOngoingRequest().get.promise.future
      bspSwitchF = server.executeCommand(ServerCommands.BspSwitch)
      _ <- bloopConnectF
      _ = assert(!server.server.indexingPromise.isCompleted)
      _ <- bspSwitchF
      _ = assert(server.server.indexingPromise.isCompleted)
      _ = assert(server.server.bspSession.exists(_.main.isSbt))
    } yield ()
  }

}
