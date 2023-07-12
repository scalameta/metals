package tests.scalacli

import scala.concurrent.Future

import scala.meta.internal.metals.FileOutOfScalaCliBspScope
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.SlowTaskConfig
import scala.meta.internal.metals.StatusBarConfig
import scala.meta.internal.metals.scalacli.ScalaCli
import scala.meta.internal.metals.{BuildInfo => V}

import tests.FileLayout

class ScalaCliSuite extends BaseScalaCliSuite(V.scala3) {
  override def serverConfig: MetalsServerConfig =
    MetalsServerConfig.default.copy(
      slowTask = SlowTaskConfig.on,
      statusBar = StatusBarConfig.showMessage,
    )

  private def simpleFileTest(useBsp: Boolean): Future[Unit] =
    for {
      _ <- scalaCliInitialize(useBsp)(simpleFileLayout)
      _ <- server.didOpen("MyTests.scala")
      _ <- {
        if (useBsp) Future.unit
        else server.executeCommand(ServerCommands.StartScalaCliServer)
      }

      // via Scala CLI-generated Semantic DB
      _ <- assertDefinitionAtLocation(
        "MyTests.scala",
        "val tests = Test@@s",
        "utest/Tests.scala",
      )

      // via Scala CLI-generated Semantic DB, to a .sc file
      _ <- assertDefinitionAtLocation(
        "MyTests.scala",
        "(new Fo@@o).value",
        "foo.sc",
        0,
      )
      _ <- assertDefinitionAtLocation(
        "MyTests.scala",
        "(new Foo).va@@lue",
        "foo.sc",
        1,
      )

      // via presentation compiler, using the Scala CLI build target classpath
      _ <- assertDefinitionAtLocation(
        "utest/Tests.scala",
        "import utest.framework.{TestCallTree, Tr@@ee}",
        "utest/framework/Tree.scala",
      )

      completion <- server.completion(
        "MyTests.scala",
        "//> using lib \"com.lihao@@yi::utest",
      )

      _ = assertNoDiff(completion, "com.lihaoyi")

      completion <- server.completion(
        "MyTests.scala",
        "//> using lib com.lihaoyi::pprin@@t",
      )

      _ = assertNoDiff(
        completion,
        """|pprint
           |pprint_native0.4
           |pprint_sjs1
           |""".stripMargin,
      )

    } yield ()

  private def simpleScriptTest(useBsp: Boolean): Future[Unit] =
    for {
      _ <- scalaCliInitialize(useBsp)(
        s"""/MyTests.sc
           |#!/usr/bin/env -S scala-cli shebang --java-opt -Xms256m --java-opt -XX:MaxRAMPercentage=80 
           |//> using scala "$scalaVersion"
           |//> using lib "com.lihaoyi::utest::0.7.10"
           |//> using lib com.lihaoyi::pprint::0.6.6
           |
           |import foo.Foo
           |import utest._
           | 
           |pprint.log(2) // top-level statement should be fine in a script
           |
           |object MyTests extends TestSuite {
           |  pprint.log(2)
           |  val tests = Tests {
           |    test("foo") {
           |      assert(2 + 2 == 4)
           |    }
           |    test("nope") {
           |      assert(2 + 2 == (new Foo).value)
           |    }
           |  }
           |}
           |
           |/foo.sc
           |class Foo {
           |  def value = 5
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("MyTests.sc")
      _ <- waitForImport(useBsp)

      // via Scala CLI-generated Semantic DB
      _ <- assertDefinitionAtLocation(
        "MyTests.sc",
        "val tests = Test@@s",
        "utest/Tests.scala",
      )

      // via Scala CLI-generated Semantic DB, to a .sc file
      _ <- assertDefinitionAtLocation(
        "MyTests.sc",
        "(new Fo@@o).value",
        "foo.sc",
        0,
      )
      _ <- assertDefinitionAtLocation(
        "MyTests.sc",
        "(new Foo).va@@lue",
        "foo.sc",
        1,
      )

      // via presentation compiler, using the Scala CLI build target classpath
      _ <- assertDefinitionAtLocation(
        "utest/Tests.scala",
        "import utest.framework.{TestCallTree, Tr@@ee}",
        "utest/framework/Tree.scala",
      )
      // make sure we don't get errors connected to shebang
      parserDiagnostics = client.diagnostics
        .get(workspace.resolve("MyTests.sc"))
        .toList
        .flatten
        .filter(_.getSource() == "scalameta")
      _ = assert(
        parserDiagnostics.isEmpty,
        s"Expected no scalameta errors, got: $parserDiagnostics",
      )

      completion <- server.completion(
        "MyTests.sc",
        "//> using lib \"com.lihao@@yi::utest",
      )

      _ = assertNoDiff(completion, "com.lihaoyi")

      completion <- server.completion(
        "MyTests.sc",
        "//> using lib com.lihaoyi::pprin@@t",
      )

      _ = assertNoDiff(
        completion,
        """|pprint
           |pprint_native0.4
           |pprint_sjs1
           |""".stripMargin,
      )

    } yield ()

  private val simpleFileLayout =
    s"""|/MyTests.scala
        |//> using scala "$scalaVersion"
        |//> using lib "com.lihaoyi::utest::0.7.10"
        |//> using lib com.lihaoyi::pprint::0.6.6
        |
        |import foo.Foo
        |import utest._
        |
        |object MyTests extends TestSuite {
        |  pprint.log(2)
        |  val tests = Tests {
        |    test("foo") {
        |      assert(2 + 2 == 4)
        |    }
        |    test("nope") {
        |      assert(2 + 2 == (new Foo).value)
        |    }
        |  }
        |}
        |
        |/foo.sc
        |class Foo {
        |  def value = 5
        |}
        |""".stripMargin

  test(s"simple-file-bsp") {
    simpleFileTest(useBsp = true)
  }

  test(s"simple-file-manual") {
    simpleFileTest(useBsp = false)
  }

  test(s"simple-script-bsp") {
    simpleScriptTest(useBsp = true)
  }

  test(s"simple-script-manual") {
    simpleScriptTest(useBsp = false)
  }

  test("connecting-scalacli") {
    cleanWorkspace()
    for {
      _ <- server.initialize()
      _ <- server.initialized()
      _ = FileLayout.fromString(simpleFileLayout, workspace)
      _ = FileLayout.fromString(bspLayout, workspace)
      _ <- server.server.indexingPromise.future
      _ <- server.didOpen("MyTests.scala")
      _ <- assertDefinitionAtLocation(
        "MyTests.scala",
        "val tests = Test@@s",
        "utest/Tests.scala",
      )
    } yield ()
  }

  test("connecting-scalacli-as-fallback") {
    cleanWorkspace()
    FileLayout.fromString(simpleFileLayout, workspace)
    for {
      _ <- server.initialize()
      _ <- server.initialized()
      _ <- server.server.indexingPromise.future
      _ <- server.didOpen("MyTests.scala")
      _ <- assertDefinitionAtLocation(
        "MyTests.scala",
        "new Fo@@o",
        "foo.sc",
      )
    } yield ()
  }

  test("relative-semanticdb-root") {
    for {
      _ <- scalaCliInitialize(useBsp = false)(
        s"""/scripts/MyTests.scala
           |//> using scala "$scalaVersion"
           |//> using lib "com.lihaoyi::utest::0.7.10"
           |//> using lib com.lihaoyi::pprint::0.6.6
           |
           |import foo.Foo
           |import utest._
           |
           |object MyTests extends TestSuite {
           |  pprint.log(2)
           |  val tests = Tests {
           |    test("foo") {
           |      assert(2 + 2 == 4)
           |    }
           |    test("nope") {
           |      assert(2 + 2 == (new Foo).value)
           |    }
           |  }
           |}
           |
           |/scripts/foo.sc
           |class Foo {
           |  def value = 5
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("scripts/MyTests.scala")
      _ <- server.executeCommand(ServerCommands.StartScalaCliServer)

      // via Scala CLI-generated Semantic DB, to a .sc file
      _ <- assertDefinitionAtLocation(
        "scripts/MyTests.scala",
        "(new Fo@@o).value",
        "scripts/foo.sc",
        0,
      )
    } yield ()
  }

  test("add-sbt") {
    cleanWorkspace()
    for {
      _ <- scalaCliInitialize(useBsp = true)(
        s"""/src/Main.scala
           |object Main:
           |  def foo = 3
           |""".stripMargin
      )
      _ = assert(
        server.server.tables.buildTool.selectedBuildTool().contains("scala-cli")
      )
      _ = assert(
        server.server.tables.buildServers.selectedServer().contains("scala-cli")
      )
      _ = FileLayout.fromString(
        s"""|/build.sbt
            |ThisBuild / scalaVersion     := "3.3.0"
            |ThisBuild / version          := "0.1.0-SNAPSHOT"
            |
            |lazy val root = (project in file("."))
            |""".stripMargin,
        workspace,
      )
      _ = client.switchBuildTool = Messages.NewBuildToolDetected.switch
      _ = client.importBuild = Messages.ImportBuild.yes
      _ <- server.didSave("build.sbt")(identity)
      _ = assert(
        server.server.tables.buildTool.selectedBuildTool().contains("sbt")
      )
      _ = assert(server.server.tables.buildServers.selectedServer().isEmpty)
      _ = assert(server.server.bspSession.exists(_.main.isBloop))
    } yield ()
  }

  test("detect-when-project-scala-file") {
    cleanWorkspace()
    server.client.importBuild = Messages.ImportBuild.yes
    for {
      _ <- initialize(
        s"""|/project.scala
            |//> using scala "${V.scala3}"
            |//> using lib "com.lihaoyi::utest::0.8.1"
            |//> using lib "com.lihaoyi::pprint::0.8.1"
            |
            |/test/MyTests.scala
            |
            |import foo.Foo
            |import utest._
            |
            |object MyTests extends TestSuite {
            |  pprint.log(2)
            |  val tests = Tests {
            |    test("foo") {
            |      assert(2 + 2 == 4)
            |    }
            |    test("nope") {
            |      assert(2 + 2 == (new Foo).value)
            |    }
            |  }
            |}
            |
            |/main/Foo.scala
            |class Foo {
            |  def value = 5
            |}
            |
            |""".stripMargin
      )
      _ <- server.server.indexingPromise.future
      _ = assert(server.server.bspSession.exists(_.main.isScalaCLI))
      _ <- server.didOpen("test/MyTests.scala")
      _ <- assertDefinitionAtLocation(
        "test/MyTests.scala",
        "val tests = Test@@s",
        "utest/Tests.scala",
      )
    } yield ()
  }

  test("single-file-config") {
    cleanWorkspace()
    val msg = FileOutOfScalaCliBspScope.askToRegenerateConfigAndRestartBspMsg(
      "File: SomeFile.scala"
    )
    def workspaceMsgs =
      (server.client.messageRequests.asScala ++ server.client.showMessages.asScala
        .map(_.getMessage()))
        .collect {
          case `msg` => msg
          case msg @ "scala-cli bspConfig" => msg
        }
        .mkString("\n")
    def hasBuildTarget(fileName: String) = server.server.buildTargets
      .inverseSources(workspace.resolve(fileName))
      .isDefined
    for {
      _ <- scalaCliInitialize(useBsp = false)(
        s"""/src/Main.scala
           |object Main:
           |  def foo = 3
           |  val m = foo
           |/SomeFile.scala
           |object Other:
           |  def foo = 3
           |  val m = foo
           |/.bsp/scala-cli.json
           |${ScalaCli.scalaCliBspJsonContent(root = workspace.resolve("src/Main.scala").toString())}
           |/.scala-build/ide-inputs.json
           |${BaseScalaCliSuite.scalaCliIdeInputJson(".")}
           |""".stripMargin
      )
      _ <- server.didOpen("src/Main.scala")
      _ = assertNoDiff(workspaceMsgs, "")
      _ = assert(hasBuildTarget("src/Main.scala"))
      _ = assert(!hasBuildTarget("SomeFile.scala"))

      _ <- server.didOpen("SomeFile.scala")
      _ <- server.server.buildServerPromise.future
      _ = assertNoDiff(workspaceMsgs, msg)
      _ = assert(!hasBuildTarget("SomeFile.scala"))

      _ = server.client.regenerateAndRestartScalaCliBuildSever =
        FileOutOfScalaCliBspScope.regenerateAndRestart
      _ <- server.didOpen("SomeFile.scala")
      _ <- server.server.buildServerPromise.future
      _ = assertNoDiff(
        workspaceMsgs,
        List(msg, msg, "scala-cli bspConfig").mkString("\n"),
      )
      _ = assert(hasBuildTarget("src/Main.scala"))
      _ = assert(hasBuildTarget("SomeFile.scala"))

      _ <- server.didOpen("SomeFile.scala")
      _ = assertNoDiff(
        workspaceMsgs,
        List(msg, msg, "scala-cli bspConfig").mkString("\n"),
      )
    } yield ()
  }

}
