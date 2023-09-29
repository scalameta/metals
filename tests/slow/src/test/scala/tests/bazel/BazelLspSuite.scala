package tests.bazel

import scala.concurrent.Promise

import scala.meta.internal.builds.BazelBuildTool
import scala.meta.internal.builds.BazelDigest
import scala.meta.internal.metals.Messages._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ServerCommands
import scala.meta.io.AbsolutePath

import tests.BaseImportSuite
import tests.BazelBuildLayout
import tests.BazelServerInitializer

class BazelLspSuite
    extends BaseImportSuite("bazel-import", BazelServerInitializer) {
  val scalaVersion = "2.13.6"
  val buildTool: BazelBuildTool = BazelBuildTool(() => userConfig, workspace)

  val bazelVersion = "6.3.2"

  val libPath = "src/main/scala/lib"
  val cmdPath = "src/main/scala/cmd"

  def bazelBspConfig: AbsolutePath = workspace.resolve(".bsp/bazelbsp.json")

  override def currentDigest(
      workspace: AbsolutePath
  ): Option[String] = BazelDigest.current(workspace)

  test("basic") {
    cleanWorkspace()
    for {
      _ <- initialize(
        BazelBuildLayout(workspaceLayout, scalaVersion, bazelVersion)
      )
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        List(
          importBuildMessage,
          // create .bazelbsp progress message
          BazelBuildTool.mainClass,
          multipleProblemsDetectedMessage,
        ).mkString("\n"),
      )
      _ = assert(bazelBspConfig.exists)
      _ = client.messageRequests.clear() // restart
      _ = assertStatus(_.isInstalled)
      _ <- server.didChange("WORKSPACE")(_ + "\n# comment")
      _ <- server.didSave("WORKSPACE")(identity)
      // Comment changes do not trigger "re-import project" request
      _ = assertNoDiff(client.workspaceMessageRequests, "")
      _ <- server.didChange(s"$cmdPath/BUILD") { text =>
        text.replace("runner", "runner1")
      }
      _ = assertNoDiff(client.workspaceMessageRequests, "")
      _ = client.importBuildChanges = ImportBuildChanges.yes
      _ <- server.didSave(s"$cmdPath/BUILD")(identity)
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
    writeLayout(BazelBuildLayout(workspaceLayout, scalaVersion, bazelVersion))
    for {
      _ <- server.initialize()
      _ <- server.initialized()
      _ = assertNoDiff(client.workspaceMessageRequests, importBuildMessage)
      _ = client.messageRequests.clear()
      // We dismissed the import request, so bsp should not be configured
      _ = assert(!bazelBspConfig.exists)
      _ <- server.didChange(s"$cmdPath/BUILD") { text =>
        text.replace("runner", "runner1")
      }
      _ <- server.didSave(s"$cmdPath/BUILD")(identity)
      _ = assertNoDiff(client.workspaceMessageRequests, "")
      _ = server.server.buildServerPromise = Promise()
      _ <- server.executeCommand(ServerCommands.GenerateBspConfig)
      // We need to wait a bit just to ensure the connection is made
      _ <- server.server.buildServerPromise.future
    } yield {
      assertNoDiff(
        client.workspaceMessageRequests,
        List(
          "bazelbsp bspConfig",
          multipleProblemsDetectedMessage,
        ).mkString("\n"),
      )
      assert(bazelBspConfig.exists)
      server.assertBuildServerConnection()
    }
  }

  test("import-build") {
    cleanWorkspace()
    writeLayout(BazelBuildLayout(workspaceLayout, scalaVersion, bazelVersion))
    for {
      _ <- server.initialize()
      _ <- server.initialized()
      _ = assertNoDiff(client.workspaceMessageRequests, importBuildMessage)
      _ = client.messageRequests.clear()
      // We dismissed the import request, so bsp should not be configured
      _ = assert(!bazelBspConfig.exists)
      _ <- server.didChange(s"$cmdPath/BUILD") { text =>
        text.replace("runner", "runner1")
      }
      _ <- server.didSave(s"$cmdPath/BUILD")(identity)
      _ = assertNoDiff(client.workspaceMessageRequests, "")
      _ = server.server.buildServerPromise = Promise()
      _ <- server.executeCommand(ServerCommands.ImportBuild)
      // We need to wait a bit just to ensure the connection is made
      _ <- server.server.buildServerPromise.future
    } yield {
      assertNoDiff(
        client.workspaceMessageRequests,
        List(
          BazelBuildTool.mainClass,
          multipleProblemsDetectedMessage,
        ).mkString("\n"),
      )
      assert(bazelBspConfig.exists)
      server.assertBuildServerConnection()
    }
  }

  private val greetingFile =
    """|package lib
       |
       |object Greeting {
       |  def greet: String = "Hi"
       |
       |  def sayHi = println(s"$greet!")
       |}
       |""".stripMargin

  private val runnerFile =
    """|package cmd
       |
       |import lib.Greeting
       |
       |object Runner {
       |  def main(args: Array[String]): Unit = {
       |    Greeting.sayHi
       |  }
       |}
       |""".stripMargin

  private val workspaceLayout =
    s"""|/$libPath/BUILD
        |load("@io_bazel_rules_scala//scala:scala.bzl", "scala_library")
        |
        |scala_library(
        |    name = "greeting",
        |    srcs = ["Greeting.scala"],
        |    visibility = ["//src/main/scala/cmd:__pkg__"],
        |)
        |
        |/$libPath/Greeting.scala
        |$greetingFile
        |
        |/$cmdPath/BUILD
        |load("@io_bazel_rules_scala//scala:scala.bzl", "scala_binary")
        |
        |scala_binary(
        |    name = "runner",
        |    main_class = "cmd.Runner",
        |    srcs = ["Runner.scala"],
        |    deps = ["//src/main/scala/lib:greeting"],
        |)
        |
        |/$cmdPath/Runner.scala
        |$runnerFile
        |""".stripMargin
}
