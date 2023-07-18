package tests.bazel

import scala.meta.internal.builds.BazelBuildTool

import scala.meta.internal.metals.Messages._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

import tests.BaseImportSuite
import scala.meta.internal.builds.BazelDigest
import tests.BazelServerInitializer
import tests.BazelBuildLayout

class BazelLspSuite
    extends BaseImportSuite("bazel-import", BazelServerInitializer) {
  val scalaVersion = "2.13.6"
  val buildTool: BazelBuildTool = BazelBuildTool(() => userConfig)

  val bazelVersion = "6.2.1"

  val libPath = "src/main/scala/lib"
  val cmdPath = "src/main/scala/cmd"

  override def currentDigest(
      workspace: AbsolutePath
  ): Option[String] = BazelDigest.current(workspace)

  test("basic") {
    cleanWorkspace()
    for {
      _ <- initialize(BazelBuildLayout(workspaceLayout, scalaVersion, bazelVersion))
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        List(
          importBuildMessage,
          // create .bazelbsp progress message
          BazelBuildTool.mainClass,
          allProjectsMisconfigured,
        ).mkString("\n"),
      )
      _ = assert(workspace.resolve(".bsp/bazelbsp.json").exists)
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
