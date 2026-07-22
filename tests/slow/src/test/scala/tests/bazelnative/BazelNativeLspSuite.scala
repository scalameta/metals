package tests.bazelnative

import scala.meta.internal.builds.BazelDigest
import scala.meta.internal.builds.BazelNativeBuildTool
import scala.meta.internal.metals.Messages._
import scala.meta.io.AbsolutePath

import tests.BaseBazelNativeServerSuite
import tests.BaseImportSuite
import tests.BazelNativeBuildLayout
import tests.BazelNativeServerInitializer

class BazelNativeLspSuite
    extends BaseImportSuite(
      "bazel-native-import",
      BazelNativeServerInitializer,
    )
    with BaseBazelNativeServerSuite {
  lazy val buildTool: BazelNativeBuildTool =
    BazelNativeBuildTool(() => userConfig, workspace)

  val bazelVersion = "6.4.0"

  def bazelNativeBspConfig: AbsolutePath =
    workspace.resolve(s".bsp/${BazelNativeBuildTool.bspName}.json")

  override def currentDigest(
      workspace: AbsolutePath
  ): Option[String] = BazelDigest.current(workspace)

  override def afterEach(context: AfterEach): Unit = {
    super.afterEach(context)
    cleanBazelServer()
  }

  val importMessage: String =
    GenerateBspAndConnect
      .params(BazelNativeBuildTool.name, BazelNativeBuildTool.bspName)
      .getMessage()

  test("basic") {
    cleanWorkspace()
    for {
      _ <- initialize(
        BazelNativeBuildLayout(workspaceLayout, "2.13.12", bazelVersion)
      )
      _ = assert(
        bazelNativeBspConfig.isFile,
        s"Expected ${bazelNativeBspConfig} to exist",
      )
      _ = assertStatus(_.isInstalled)
    } yield {
      server.assertBuildServerConnection()
    }
  }

  private val commonCode =
    """|scala_library(
       |    name = "hello_lib",
       |    srcs = ["Hello.scala"],
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
       |}
       |
       |/Main.scala
       |import examples.scala3.Hello
       |
       |object Main {
       |  def msg = new Hello().hello
       |}
       |
       |""".stripMargin

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
        |$commonCode
        |""".stripMargin
}
