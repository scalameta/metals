package tests.bazel

import scala.concurrent.Promise

import scala.meta.internal.builds.BazelBuildTool
import scala.meta.internal.builds.BazelDigest
import scala.meta.internal.metals.FileDecoderProvider
import scala.meta.internal.metals.Messages._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.io.AbsolutePath

import tests.BaseImportSuite
import tests.BazelBuildLayout
import tests.BazelServerInitializer

class BazelLspSuite
    extends BaseImportSuite("bazel-import", BazelServerInitializer) {
  val buildTool: BazelBuildTool = BazelBuildTool(() => userConfig, workspace)

  val bazelVersion = "6.4.0"

  def bazelBspConfig: AbsolutePath = workspace.resolve(".bsp/bazelbsp.json")

  override def currentDigest(
      workspace: AbsolutePath
  ): Option[String] = BazelDigest.current(workspace)

  test("basic") {
    cleanWorkspace()
    for {
      _ <- initialize(
        BazelBuildLayout(workspaceLayout, V.scala213, bazelVersion)
      )
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        List(
          importBuildMessage,
          // create .bazelbsp progress message
          BazelBuildTool.mainClass,
          bazelNavigationMessage,
        ).mkString("\n"),
      )
      _ = assert(bazelBspConfig.exists)
      _ = client.messageRequests.clear() // restart
      _ = assertStatus(_.isInstalled)
      _ = assertNoDiff(client.workspaceDiagnostics, "")
      _ <- server.didChange("WORKSPACE")(_ + "\n# comment")
      _ <- server.didSave("WORKSPACE")(identity)
      // Comment changes do not trigger "re-import project" request
      _ = assertNoDiff(client.workspaceMessageRequests, "")
      _ <- server.didChange("Hello.scala") { text =>
        text.replace("def hello: String", "def hello: Int")
      }
      _ <- server.didSave("Hello.scala")(identity)
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|Hello.scala:4:20: error: type mismatch;
           | found   : String("Hello")
           | required: Int
           |  def hello: Int = "Hello"
           |                   ^
           |  def hello: Int = "Hello"
           |                   ^
           |""".stripMargin,
      )
      _ <- server.didChange(s"BUILD") { text =>
        text.replace("\"main\"", "\"main1\"")
      }
      _ = assertNoDiff(client.workspaceMessageRequests, "")
      _ = client.importBuildChanges = ImportBuildChanges.yes
      _ <- server.didSave(s"BUILD")(identity)
    } yield {
      assertNoDiff(
        client.workspaceMessageRequests,
        List(
          importBuildChangesMessage
        ).mkString("\n"),
      )
      server.assertBuildServerConnection()
    }
  }

  test("generate-bsp-config") {
    cleanWorkspace()
    writeLayout(
      BazelBuildLayout(workspaceLayout, V.scala213, bazelVersion)
    )
    for {
      _ <- server.initialize()
      _ <- server.initialized()
      _ = assertNoDiff(client.workspaceMessageRequests, importBuildMessage)
      _ = client.messageRequests.clear()
      // We dismissed the import request, so bsp should not be configured
      _ = assert(!bazelBspConfig.exists)
      _ <- server.didChange(s"BUILD") { text =>
        text.replace("\"main\"", "\"main1\"")
      }
      _ <- server.didSave(s"BUILD")(identity)
      _ = assertNoDiff(client.workspaceMessageRequests, "")
      _ = server.server.buildServerPromise = Promise()
      _ <- server.executeCommand(ServerCommands.GenerateBspConfig)
      // We need to wait a bit just to ensure the connection is made
      _ <- server.server.buildServerPromise.future
      _ <- server.didChange("Hello.scala") { text =>
        text.replace("def hello: String", "def hello: Int")
      }
      _ <- server.didSave("Hello.scala")(identity)
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|Hello.scala:4:20: error: type mismatch;
           | found   : String("Hello")
           | required: Int
           |  def hello: Int = "Hello"
           |                   ^
           |  def hello: Int = "Hello"
           |                   ^
           |""".stripMargin,
      )
    } yield {
      assertNoDiff(
        client.workspaceMessageRequests,
        List(
          "bazelbsp bspConfig",
          bazelNavigationMessage,
        ).mkString("\n"),
      )
      assert(bazelBspConfig.exists)
      server.assertBuildServerConnection()
    }
  }

  test("import-build") {
    cleanWorkspace()
    writeLayout(
      BazelBuildLayout(workspaceLayout, V.scala213, bazelVersion)
    )
    for {
      _ <- server.initialize()
      _ <- server.initialized()
      _ = assertNoDiff(client.workspaceMessageRequests, importBuildMessage)
      _ = client.messageRequests.clear()
      // We dismissed the import request, so bsp should not be configured
      _ = assert(!bazelBspConfig.exists)
      _ <- server.didChange(s"BUILD") { text =>
        text.replace("\"main\"", "\"main1\"")
      }
      _ <- server.didSave(s"BUILD")(identity)
      _ = assertNoDiff(client.workspaceMessageRequests, "")
      _ = server.server.buildServerPromise = Promise()
      _ <- server.executeCommand(ServerCommands.ImportBuild)
      // We need to wait a bit just to ensure the connection is made
      _ <- server.server.buildServerPromise.future
      targets <- server.listBuildTargets
      result <- server.executeDecodeFileCommand(
        FileDecoderProvider
          .createBuildTargetURI(workspace, targets.head.bazelEscapedDisplayName)
          .toString
      )
      _ = assertNoDiff(
        result.value.linesIterator.take(14).mkString("\n"),
        """|Target
           |  @//:lib
           |
           |Tags
           |  library
           |
           |Languages
           |  scala
           |
           |Capabilities
           |  Debug <- NOT SUPPORTED
           |  Run <- NOT SUPPORTED
           |  Test <- NOT SUPPORTED
           |  Compile""".stripMargin,
      )
    } yield {
      assertNoDiff(
        client.workspaceMessageRequests,
        List(
          BazelBuildTool.mainClass,
          bazelNavigationMessage,
        ).mkString("\n"),
      )
      assert(bazelBspConfig.exists)
      server.assertBuildServerConnection()
    }
  }

  private val workspaceLayout =
    s"""|/BUILD
        |load("@io_bazel_rules_scala//scala:scala.bzl", "scala_binary", "scala_library")
        |
        |scala_library(
        |    name = "lib",
        |    srcs = ["Hello.scala"],
        |    deps = [],
        |)
        |
        |scala_binary(
        |    name = "main",
        |    srcs = ["Main.scala"],
        |    main_class = "hello",
        |    deps = [":lib"],
        |)
        |
        |/Hello.scala
        |package examples.scala3
        |
        |class Hello {
        |  def hello: String = "Hello"
        |
        |}
        |
        |
        |/Main.scala
        |import examples.scala3.Hello
        |
        |object Main {
        |def msg = new Hello().hello
        |}
        |
        |""".stripMargin

}
