package tests.scalacli

import scala.concurrent.Future

import scala.meta.internal.metals.DebugUnresolvedMainClassParams
import scala.meta.internal.metals.FileOutOfScalaCliBspScope
import scala.meta.internal.metals.InitializationOptions
import scala.meta.internal.metals.JsonParser._
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.debug.TestDebugger
import scala.meta.internal.metals.scalacli.ScalaCli
import scala.meta.internal.mtags.CoursierComplete

import org.eclipse.{lsp4j => l}
import tests.FileLayout

// https://github.com/scalameta/metals/issues/6839
class ScalaCliSuite extends BaseScalaCliSuite("3.3.3") {

  override protected def initializationOptions: Option[InitializationOptions] =
    Some(
      InitializationOptions.Default.copy(
        inlineDecorationProvider = Some(true),
        decorationProvider = Some(true),
        debuggingProvider = Option(true),
        runProvider = Option(true),
      )
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
        "//> using dep \"com.lihao@@yi::utest",
      )

      _ = assertNoDiff(completion, "com.lihaoyi")

      completion <- server.completion(
        "MyTests.scala",
        "//> using dep com.lihaoyi::pprin@@t",
      )

      _ = assertNoDiff(
        completion,
        """|pprint
           |pprint_native0.4
           |pprint_native0.5
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
           |//> using dep "com.lihaoyi::utest::0.7.10"
           |//> using dep com.lihaoyi::pprint::0.6.6
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
        "//> using dep \"com.lihao@@yi::utest",
      )

      _ = assertNoDiff(completion, "com.lihaoyi")

      completion <- server.completion(
        "MyTests.sc",
        "//> using dep com.lihaoyi::pprin@@t",
      )

      _ = assertNoDiff(
        completion,
        """|pprint
           |pprint_native0.4
           |pprint_native0.5
           |pprint_sjs1
           |""".stripMargin,
      )

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

      _ <- server.didOpen("MyTests.sc")
      _ <- server.assertInlayHints(
        "MyTests.sc",
        s"""|#!/usr/bin/env -S scala-cli shebang --java-opt -Xms256m --java-opt -XX:MaxRAMPercentage=80 
            |//> using scala "$scalaVersion"
            |//> using dep "com.lihaoyi::utest::0.7.10"
            |//> using dep com.lihaoyi::pprint::0.6.6
            |
            |import foo.Foo
            |import utest._
            | 
            |pprint.log/*[Int<<scala/Int#>>]*/(2)/*(using generate<<sourcecode/LineMacros#generate().>>, generate<<sourcecode/FileNameMacros#generate().>>)*/ // top-level statement should be fine in a script
            |
            |object MyTests extends TestSuite {
            |  pprint.log/*[Int<<scala/Int#>>]*/(2)/*(using generate<<sourcecode/LineMacros#generate().>>, generate<<sourcecode/FileNameMacros#generate().>>)*/
            |  val tests/*: Tests<<utest/Tests#>>*/ = Tests {
            |    test("foo") {
            |      assert(2 + 2 == 4)
            |    }
            |    test("nope") {
            |      assert(2 + 2 == (new Foo).value)
            |    }
            |  }
            |}
            |""".stripMargin,
      )
    } yield ()

  private val simpleFileLayout =
    s"""|/MyTests.scala
        |//> using scala "$scalaVersion"
        |//> using dep "com.lihaoyi::utest::0.7.10"
        |//> using dep com.lihaoyi::pprint::0.6.6
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
    cleanWorkspace()
    simpleFileTest(useBsp = true)
  }

  test(s"simple-file-manual") {
    simpleFileTest(useBsp = false)
  }

  test(s"simple-script-bsp".flaky) {
    cleanWorkspace()
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
      _ <- server.fullServer
        .didChangeWatchedFiles(
          new l.DidChangeWatchedFilesParams(
            List(
              new l.FileEvent(
                workspace.resolve(".bsp/scala-cli.json").toURI.toString(),
                l.FileChangeType.Created,
              )
            ).asJava
          )
        )
        .asScala
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
    } yield {
      val indexingCount = client.progressParams
        .stream()
        .filter { params =>
          if (params.getValue().isLeft()) {
            params.getValue().getLeft() match {
              case begin: l.WorkDoneProgressBegin =>
                begin.getTitle() == "Indexing"
              case _ => false
            }
          } else false
        }
        .count()
        .toInt
      assertEquals(indexingCount, 1, "should index only once")
    }
  }

  test("inner") {
    cleanWorkspace()
    for {
      _ <- scalaCliInitialize(useBsp = false)(
        s"""|/inner/project.scala
            |//> using scala "$scalaVersion"
            |//> using dep "com.lihaoyi::utest::0.8.1"
            |/inner/MyTests.scala
            |import utest._
            |
            |object MyTests extends TestSuite {
            |  val tests = Tests {
            |    test("foo") {
            |      assert(2 + 2 == 4)
            |    }
            |  }
            |}
            |""".stripMargin
      )
      _ <- server.didOpen("inner/MyTests.scala")
      _ = assert(!client.workspaceDiagnostics.contains("Not found: utest"))
    } yield ()
  }

  test("relative-semanticdb-root") {
    for {
      _ <- scalaCliInitialize(useBsp = false)(
        s"""/scripts/MyTests.scala
           |//> using scala "$scalaVersion"
           |//> using dep "com.lihaoyi::utest::0.7.10"
           |//> using dep com.lihaoyi::pprint::0.6.6
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
            |//> using scala "${scalaVersion}"
            |//> using dep "com.lihaoyi::utest::0.8.1"
            |//> using dep "com.lihaoyi::pprint::0.8.1"
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

  val coursierComplete = new CoursierComplete(scalaVersion)
  val newestToolkit: String = coursierComplete
    .complete("org.scala-lang::toolkit:")
    .headOption
    .map(_.split(":").last)
    .getOrElse("default")

  test("properly-reindex") {
    cleanWorkspace()
    server.client.importBuild = Messages.ImportBuild.yes
    for {
      _ <- scalaCliInitialize(useBsp = true)(
        s"""|/Main.scala
            |//> using scala ${scalaVersion}
            |// > using toolkit ${newestToolkit}
            |
            |object Main {
            |    println(os.pwd)
            |}
            |
            |""".stripMargin
      )
      _ <- server.server.buildServerPromise.future
      _ <- server.didOpen("Main.scala")
      _ = assertEquals(
        server.client.workspaceDiagnostics,
        """|Main.scala:5:13: error: Not found: os
           |    println(os.pwd)
           |            ^^
           |""".stripMargin,
      )
      _ <- server.didSave("Main.scala") { text =>
        text.replace("// >", "//>")
      }
      // cause another compilation to wait on workspace reload, the previous gets cancelled
      _ <- server.didSave("Main.scala")(identity)
      _ = assertEquals(
        server.client.workspaceDiagnostics,
        "",
      )
    } yield ()
  }

  test("single-file-config") {
    cleanWorkspace()
    val msg = FileOutOfScalaCliBspScope.askToRegenerateConfigAndRestartBspMsg(
      "File: SomeFile.scala"
    )
    def workspaceMsgs =
      server.client.messageRequests.asScala
        .filter(
          _ != "scala-cli bspConfig"
        ) // to achieve the same behavior no matter if scala-cli in installed or not
        .mkString("\n")
    def hasBuildTarget(fileName: String) = server.server.buildTargets
      .inverseSources(workspace.resolve(fileName))
      .isDefined
    for {
      _ <- initialize(
        s"""/src/Main.scala
           |object Main:
           |  def foo = 3
           |  val m = foo
           |/SomeFile.scala
           |object Other:
           |  def foo = 3
           |  val m = foo
           |/.bsp/scala-cli.json
           |${ScalaCli.scalaCliBspJsonContent(projectRoot = workspace.resolve("src/Main.scala").toString())}
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
        List(msg, msg).mkString("\n"),
      )
      _ = assert(hasBuildTarget("src/Main.scala"))
      _ = assert(hasBuildTarget("SomeFile.scala"))

      _ <- server.didOpen("SomeFile.scala")
      _ = assertNoDiff(
        workspaceMsgs,
        List(msg, msg).mkString("\n"),
      )
    } yield ()
  }

  def startDebugging(
      main: String,
      buildTarget: String,
  ): Future[TestDebugger] = {
    server.startDebuggingUnresolved(
      new DebugUnresolvedMainClassParams(main, buildTarget).toJson
    )
  }

  test("base-native-run") {
    cleanWorkspace()
    for {
      _ <- scalaCliInitialize(useBsp = true)(
        s"""/MyMain.scala
           |//> using scala "$scalaVersion"
           |//> using platform "native"
           |
           |import scala.scalanative._
           |
           |object MyMain {
           |  def main(args: Array[String]): Unit = {
           |    println("Hello world!")
           |    System.exit(0)
           |  }
           |}
           |
           |object MyMain2 {
           |  def main(args: Array[String]): Unit = {
           |    println("Hello world!")
           |    System.exit(0)
           |  }
           |}
           |
           |""".stripMargin
      )
      _ <- server.didOpen("MyMain.scala")
      textWithLenses <- server.codeLensesText(
        "MyMain.scala",
        printCommand = false,
      )(maxRetries = 5)
      _ = assertNoDiff(
        textWithLenses,
        s"""|//> using scala "$scalaVersion"
            |//> using platform "native"
            |
            |import scala.scalanative._
            |
            |<<run>>
            |object MyMain {
            |  def main(args: Array[String]): Unit = {
            |    println("Hello world!")
            |    System.exit(0)
            |  }
            |}
            |
            |<<run>>
            |object MyMain2 {
            |  def main(args: Array[String]): Unit = {
            |    println("Hello world!")
            |    System.exit(0)
            |  }
            |}
            |
            |""".stripMargin,
      )
      targets <- server.listBuildTargets
      mainTarget = targets.find(!_.contains("test"))
      _ = assert(mainTarget.isDefined, "No main target specified")
      debugServer <- startDebugging("MyMain", mainTarget.get)
      _ <- debugServer.initialize
      _ <- debugServer.launch
      _ <- debugServer.configurationDone
      _ <- debugServer.shutdown
      output <- debugServer.allOutput
    } yield assertContains(output, "Hello world!\n")
  }

  test("cancel-native-run") {
    cleanWorkspace()
    for {
      _ <- scalaCliInitialize(useBsp = true)(
        s"""/MyMain.scala
           |//> using scala "$scalaVersion"
           |//> using platform "native"
           |
           |import scala.scalanative._
           |
           |object MyMain {
           |  def main(args: Array[String]): Unit = {
           |    println("Hello world!")
           |    while(true){} // infinite loop
           |    System.exit(0)
           |  }
           |}
           |
           |""".stripMargin
      )
      _ <- server.didOpen("MyMain.scala")
      targets <- server.listBuildTargets
      mainTarget = targets.find(!_.contains("test"))
      _ = assert(mainTarget.isDefined, "No main target specified")
      debugServer <- startDebugging("MyMain", mainTarget.get)
      _ <- debugServer.initialize
      _ <- debugServer.launch
      _ <- debugServer.configurationDone
      _ <- debugServer.disconnect
      _ <- debugServer.shutdown
      _ <- debugServer.allOutput
    } yield ()
  }

  test("base-js-run") {
    cleanWorkspace()
    for {
      _ <- scalaCliInitialize(useBsp = true)(
        s"""/MyMain.scala
           |//> using scala "$scalaVersion"
           |//> using platform "js"
           |
           |import scala.scalajs.js
           |
           |object MyMain {
           |  def main(args: Array[String]): Unit = {
           |    println("Hello world!")
           |    // System.exit(0)
           |  }
           |}
           |
           |""".stripMargin
      )
      _ <- server.didOpen("MyMain.scala")
      textWithLenses <- server.codeLensesText(
        "MyMain.scala",
        printCommand = false,
      )(maxRetries = 5)
      _ = assertNoDiff(
        textWithLenses,
        s"""|//> using scala "$scalaVersion"
            |//> using platform "js"
            |
            |import scala.scalajs.js
            |
            |<<run>>
            |object MyMain {
            |  def main(args: Array[String]): Unit = {
            |    println("Hello world!")
            |    // System.exit(0)
            |  }
            |}
            |
            |""".stripMargin,
      )
      targets <- server.listBuildTargets
      mainTarget = targets.find(!_.contains("test"))
      _ = assert(mainTarget.isDefined, "No main target specified")
      debugServer <- startDebugging("MyMain", mainTarget.get)
      _ <- debugServer.initialize
      _ <- debugServer.launch
      _ <- debugServer.configurationDone
      _ <- debugServer.shutdown
      output <- debugServer.allOutput
    } yield assertContains(output, "Hello world!\n")
  }

  test("scala-shebang") {
    cleanWorkspace()
    for {
      _ <- scalaCliInitialize(useBsp = true)(
        s"""/example.scala
           |#!usr/bin/env -S scala -cli shebang
           |//> using scala $scalaVersion
           |class O {
           |  val i = List(1, 2, 3).map(_ + 1)
           |}
           |
           |""".stripMargin
      )
      _ <- server.didOpen("example.scala")
      completions <- server.completion(
        "example.scala",
        "  val i = List(1, 2, 3).map@@(_ + 1)",
      )
      _ = assertNoDiff(
        completions,
        """|mapConserve[B >: Int <: AnyRef](f: Int => B): List[B]
           |map[B](f: Int => B): List[B]
           |""".stripMargin,
      )
    } yield ()
  }

  test("completions-for-test-scope") {
    for {
      _ <- scalaCliInitialize(useBsp = true)(
        s"""/MyTests.sc
           |//> using scala $scalaVersion
           |//> using test.dep com.lihaoyi::utest::0.7.10
           |""".stripMargin
      )
      completion <- server.completion(
        "MyTests.sc",
        "//> using test.dep com.lihao@@yi::utest",
      )
      _ = assertNoDiff(completion, "com.lihaoyi")
    } yield ()
  }
}
