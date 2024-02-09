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
        text.replace("\"hello\"", "\"hello1\"")
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
        text.replace("\"hello\"", "\"hello1\"")
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
        text.replace("\"hello\"", "\"hello1\"")
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
           |  @//:hello1
           |
           |Tags
           |  application
           |
           |Languages
           |  scala
           |
           |Capabilities
           |  Debug <- NOT SUPPORTED
           |  Run
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

  // TODO: Unignore this test
  test("references".flaky) {
    cleanWorkspace()
    for {
      _ <- initialize(
        BazelBuildLayout(workspaceLayout, V.scala213, bazelVersion)
      )
      _ <- server.didOpen("Hello.scala")
      references <- server.references("Hello.scala", "hello")
      _ = assertNoDiff(
        references,
        """|Hello.scala:4:7: info: reference
           |  def hello: String = "Hello"
           |      ^^^^^
           |Bar.scala:5:24: info: reference
           |  def hi = new Hello().hello
           |                       ^^^^^
           |""".stripMargin,
      )
      _ <- server.didOpen("Main.scala")
      references <- server.references("Hello.scala", "hello")
      _ = assertNoDiff(
        references,
        """|Hello.scala:4:7: info: reference
           |  def hello: String = "Hello"
           |      ^^^^^
           |Main.scala:4:23: info: reference
           |def msg = new Hello().hello
           |                      ^^^^^
           |Bar.scala:5:24: info: reference
           |  def hi = new Hello().hello
           |                       ^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  private val workspaceLayout =
    s"""|/BUILD
        |load("@io_bazel_rules_scala//scala:scala_toolchain.bzl", "scala_toolchain")
        |load("@io_bazel_rules_scala//scala:scala.bzl", "scala_binary", "scala_library")
        |
        |scala_toolchain(
        |    name = "semanticdb_toolchain_impl",
        |    enable_semanticdb = True,
        |    semanticdb_bundle_in_jar = False,
        |    visibility = ["//visibility:public"],
        |)
        |
        |toolchain(
        |    name = "semanticdb_toolchain",
        |    toolchain = "semanticdb_toolchain_impl",
        |    toolchain_type = "@io_bazel_rules_scala//scala:toolchain_type",
        |    visibility = ["//visibility:public"],
        |)
        |
        |scala_library(
        |    name = "hello_lib",
        |    srcs = ["Hello.scala", "Bar.scala"],
        |)
        |
        |scala_binary(
        |    name = "hello",
        |    srcs = ["Main.scala"],
        |    main_class = "main",
        |    deps = [":hello_lib"],
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
        |/Bar.scala
        |package examples.scala3
        |
        |class Bar {
        |  def bar: String = "bar"
        |  def hi = new Hello().hello
        |}
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
