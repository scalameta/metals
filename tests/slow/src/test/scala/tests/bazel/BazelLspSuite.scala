package tests.bazel

import scala.concurrent.Promise

import scala.meta.internal.builds.BazelBuildTool
import scala.meta.internal.builds.BazelDigest
import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.metals.DecoderResponse
import scala.meta.internal.metals.Directories
import scala.meta.internal.metals.Embedded
import scala.meta.internal.metals.FileDecoderProvider
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.Messages._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.Time
import scala.meta.internal.metals.WorkDoneProgress
import scala.meta.internal.metals.clients.language.NoopLanguageClient
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.TextDocumentIdentifier
import tests.BaseImportSuite
import tests.BazelBuildLayout
import tests.BazelModuleLayout
import tests.BazelServerInitializer

class BazelLspSuite
    extends BaseImportSuite("bazel-import", BazelServerInitializer) {
  val buildTool: BazelBuildTool = BazelBuildTool(() => userConfig, workspace)

  val bazelVersion = "6.4.0"

  def bazelBspConfig: AbsolutePath = workspace.resolve(".bsp/bazelbsp.json")

  override def currentDigest(
      workspace: AbsolutePath
  ): Option[String] = BazelDigest.current(workspace)

  val importMessage: String =
    GenerateBspAndConnect.params("bazel", "bazelbsp").getMessage()

  test("basic") {
    cleanWorkspace()
    for {
      _ <- initialize(
        BazelBuildLayout(workspaceLayout, V.bazelScalaVersion, bazelVersion)
      )
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        List(
          importMessage,
          Messages.DeprecatedRemovedScalaVersion.message(Set("2.13.12")),
        ).mkString("\n"),
      )
      _ = assert(bazelBspConfig.exists)
      _ = client.messageRequests.clear() // restart
      _ = assertStatus(_.isInstalled)
      _ = assertNoDiff(client.workspaceDiagnostics, "")
      _ <- server.didChange("WORKSPACE")(_ + "\n# comment")
      _ <- server.didSave("WORKSPACE")
      // Comment changes do not trigger "re-import project" request
      _ = assertNoDiff(client.workspaceMessageRequests, "")
      _ <- server.didChange("Hello.scala") { text =>
        text.replace("def hello: String", "def hello: Int")
      }
      _ <- server.didSave("Hello.scala")
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
      _ = client.generateBspAndConnect = GenerateBspAndConnect.yes
      _ <- server.didSave(s"BUILD")
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
      BazelBuildLayout(workspaceLayout, V.bazelScalaVersion, bazelVersion)
    )
    for {
      _ <- server.initialize()
      _ <- server.initialized()
      _ = assertNoDiff(client.workspaceMessageRequests, importMessage)
      _ = client.messageRequests.clear()
      // We dismissed the import request, so bsp should not be configured
      _ = assert(!bazelBspConfig.exists)
      _ <- server.didChange(s"BUILD") { text =>
        text.replace("\"hello\"", "\"hello1\"")
      }
      _ <- server.didSave(s"BUILD")
      _ = assertNoDiff(client.workspaceMessageRequests, "")
      _ = server.headServer.connectionProvider.buildServerPromise = Promise()
      _ <- server.executeCommand(ServerCommands.GenerateBspConfig)
      // We need to wait a bit just to ensure the connection is made
      _ <- server.server.buildServerPromise.future
      _ <- server.didChange("Hello.scala") { text =>
        text.replace("def hello: String", "def hello: Int")
      }
      _ <- server.didSave("Hello.scala")
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
      assertEquals(
        client.workspaceMessageRequests,
        Messages.DeprecatedRemovedScalaVersion.message(Set("2.13.12")),
      )
      assert(bazelBspConfig.exists)
      server.assertBuildServerConnection()
    }
  }

  test("import-reset-build") {
    cleanWorkspace()
    writeLayout(
      BazelBuildLayout(workspaceLayout, V.bazelScalaVersion, bazelVersion)
    )

    def getTargetInfo(target: String) = {
      server
        .executeDecodeFileCommand(
          FileDecoderProvider
            .createBuildTargetURI(workspace, target)
            .toString
        )
        .map(_.value.linesIterator.take(14).mkString("\n"))
    }
    for {
      _ <- server.initialize()
      _ <- server.initialized()
      _ = assertNoDiff(client.workspaceMessageRequests, importMessage)
      _ = client.messageRequests.clear()
      // We dismissed the import request, so bsp should not be configured
      _ = assert(!bazelBspConfig.exists)
      _ <- server.didChange(s"BUILD") { text =>
        text.replace("\"hello\"", "\"hello1\"")
      }
      _ <- server.didSave(s"BUILD")
      _ = assertNoDiff(client.workspaceMessageRequests, "")
      _ = server.headServer.connectionProvider.buildServerPromise = Promise()
      _ <- server.executeCommand(ServerCommands.ImportBuild)
      // We need to wait a bit just to ensure the connection is made
      _ <- server.server.buildServerPromise.future
      targets <- server.listBuildTargets
      result <- getTargetInfo(targets.head.bazelEscapedDisplayName)
      expectedTarget =
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
           |  Compile""".stripMargin
      _ = assertNoDiff(result, expectedTarget)
      _ = server.headServer.connectionProvider.buildServerPromise = Promise()
      _ = client.resetWorkspace = Messages.ResetWorkspace.resetWorkspace
      _ <- server.executeCommand(ServerCommands.ResetWorkspace, true)
      _ <- server.server.buildServerPromise.future
      resultAfter <- getTargetInfo(targets.head.bazelEscapedDisplayName)
      _ = assertNoDiff(resultAfter, expectedTarget)
    } yield {
      assertNoDiff(
        client.workspaceMessageRequests,
        List(
          Messages.DeprecatedRemovedScalaVersion.message(Set("2.13.12"))
        ).mkString("\n"),
      )
      assert(bazelBspConfig.exists)
      server.assertBuildServerConnection()
    }
  }

  test("references") {
    cleanWorkspace()
    for {
      _ <- initialize(
        BazelBuildLayout(workspaceLayout, V.bazelScalaVersion, bazelVersion)
      )
      _ <- server.didOpen("Hello.scala")
      _ <- server.didOpen("Main.scala")
      _ <- server.didSave("Main.scala")
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

  test("warnings") {
    cleanWorkspace()
    for {
      _ <- initialize(
        BazelBuildLayout(workspaceLayout, V.bazelScalaVersion, bazelVersion)
      )
      _ <- server.didOpen("Hello.scala")
      _ <- server.didChange("Hello.scala") { _ =>
        """|package examples.scala3
           |
           |sealed trait A
           |case class B(name: String) extends A
           |case class C(name: String) extends A
           |
           |class Hello {
           |  def hello: String = "Hello"
           |  
           |  val asd: A = ???
           |  asd match {
           |    case B(_) =>
           |  }
           |}
           |""".stripMargin
      }
      _ <- server.didSave("Hello.scala")
      _ = assertNoDiff(
        server.client.workspaceDiagnostics,
        """|Hello.scala:11:3: warning: match may not be exhaustive.
           |It would fail on the following input: C(_)
           |  asd match {
           |  ^
           |  asd match {
           |  ^
           |""".stripMargin,
      )
      // warnings should not disappear after updating
      _ <- server.didChange("Hello.scala") { text =>
        s"""|$text
            |
            |class Additional
            |""".stripMargin
      }
      _ <- server.didSave("Hello.scala")
      _ = assertNoDiff(
        server.client.workspaceDiagnostics,
        """|Hello.scala:11:3: warning: match may not be exhaustive.
           |It would fail on the following input: C(_)
           |  asd match {
           |  ^
           |  asd match {
           |  ^
           |""".stripMargin,
      )
    } yield ()
  }

  test("update-bazel-bsp") {
    cleanWorkspace()
    writeLayout(
      BazelBuildLayout(workspaceLayout, V.bazelScalaVersion, bazelVersion)
    )

    val shellRunner = new ShellRunner(
      Time.system,
      new WorkDoneProgress(NoopLanguageClient, Time.system),
      () => userConfig,
    )

    def jsonFile =
      workspace.resolve(Directories.bsp).resolve("bazelbsp.json").readText
    for {
      _ <- shellRunner
        .runJava(
          Embedded.dependencyOf(
            BazelBuildTool.dependency.module.organization.value,
            BazelBuildTool.dependency.module.name.value,
            BazelBuildTool.bspVersion,
          ),
          BazelBuildTool.mainClass,
          workspace,
          BazelBuildTool.projectViewArgs(workspace),
          None,
        )
        .future
      _ = assertContains(jsonFile, BazelBuildTool.bspVersion)
      _ <- initialize(
        BazelBuildLayout(workspaceLayout, V.bazelScalaVersion, bazelVersion)
      )
      _ <- server.didOpen("Hello.scala")
      _ <- server.didChange("Hello.scala") { text =>
        text.replace("def hello: String", "def hello: Int")
      }
      _ <- server.didSave("Hello.scala")
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
      _ = assertContains(jsonFile, BazelBuildTool.bspVersion)
    } yield ()
  }

  test("update-projectview") {
    cleanWorkspace()
    writeLayout(
      BazelBuildLayout(workspaceLayout, V.bazelScalaVersion, bazelVersion)
    )

    val projectview = workspace.resolve("projectview.bazelproject")
    projectview.touch()

    for {
      _ <- initialize(
        BazelBuildLayout(workspaceLayout, V.bazelScalaVersion, bazelVersion)
      )
      _ = { client.importBuildChanges = ImportBuildChanges.yes }
      _ <- server.didOpen("Hello.scala")
      _ = assertNoDiff(
        projectview.readText,
        BazelBuildTool.fallbackProjectView(workspace),
      )
      _ <- server.didChange("Hello.scala") { text =>
        text.replace("def hello: String", "def hello: Int")
      }
      _ <- server.didSave("Hello.scala")
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
      _ = client.messageRequests.clear()
      _ <- server.didOpen("Hello.scala")
      _ <- server.didChange("Hello.scala") { text =>
        text.replace("def hello: Int", "def hello: String")
      }
      _ <- server.didSave("Hello.scala")
      _ = assertNoDiagnostics()
      _ <- server.didOpen("projectview.bazelproject")
      _ <- server.didChange("projectview.bazelproject")(_ => "")
      _ <- server.didSave("projectview.bazelproject")
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        ImportBuildChanges.params("bazel").getMessage(),
      )
      _ = assertNoDiff(
        projectview.readText,
        BazelBuildTool.fallbackProjectView(workspace),
      )
    } yield ()
  }

  test("decode") {
    cleanWorkspace()
    for {
      _ <- initialize(
        BazelBuildLayout(workspaceLayout, V.bazelScalaVersion, bazelVersion)
      )
      _ <- server.didOpen("Decode.scala")
      uri = server.toPath("Decode.scala").toURI.toString()
      _ = client.showMessageRequestHandler =
        _.getActions().asScala.find(_.getTitle() == "Decode$.class")
      result <- server.fullServer
        .executeCommand(
          ServerCommands.ChooseClass.toExecuteCommandParams(
            ServerCommands
              .ChooseClassRequest(new TextDocumentIdentifier(uri), "class")
          )
        )
        .asScala
      cfr <- server.executeDecodeFileCommand(
        s"${result.asInstanceOf[DecoderResponse].value}.cfr"
      )
      _ = assert(cfr.value.contains("Decompiled with CFR "))
      javap <- server.executeDecodeFileCommand(
        s"${result.asInstanceOf[DecoderResponse].value}.javap"
      )
      _ = assert(javap.value.contains("""Compiled from "Decode.scala""""))
    } yield ()
  }

  test("bazel-8.2.1") {
    cleanWorkspace()
    val bazelVersion821 = "8.2.1"
    for {
      _ <- initialize(
        BazelModuleLayout(moduleWorkspaceLayout, V.scala3, bazelVersion821)
      )
      _ = assert(bazelBspConfig.exists)

      // Check that the project view uses scala_rules for Bazel 8.2.1
      projectViewFile = workspace.list.find(
        _.filename.endsWith(".bazelproject")
      )
      _ = assert(projectViewFile.isDefined, "Project view file should exist")
      projectViewContent = projectViewFile.get.readText
      _ = assertNoDiff(
        projectViewContent,
        """|targets:
           |    @//...:all
           |
           |allow_manual_targets_sync: false
           |
           |derive_targets_from_directories: false
           |
           |enabled_rules:
           |    rules_scala
           |    rules_java
           |    rules_jvm
           |
           |""".stripMargin,
      )

      _ <- server.didOpen("Hello.scala")
      _ <- server.didChange("Hello.scala") { text =>
        text.replace("def hello: String", "def hello: Int")
      }
      _ <- server.didSave("Hello.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|Hello.scala:4:19: error: Type Mismatch Error
           |Found:    ("Hello" : String)
           |Required: Int
           |
           |longer explanation available when compiling with `-explain`
           |  def hello: Int = "Hello"
           |                  ^
           |""".stripMargin,
      )
    } yield ()
  }

  test("goto-definition-into-dependency-sources") {
    cleanWorkspace()
    val bazelVersion =
      s"""|/.bazelversion
          |6.4.0
          |""".stripMargin

    val workspaceFile =
      """|/WORKSPACE
         |load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
         |
         |http_archive(
         |    name = "bazel_skylib",
         |    sha256 = "b8a1527901774180afc798aeb28c4634bdccf19c4d98e7bdd1ce79d1fe9aaad7",
         |    urls = [
         |        "https://mirror.bazel.build/github.com/bazelbuild/bazel-skylib/releases/download/1.4.1/bazel-skylib-1.4.1.tar.gz",
         |        "https://github.com/bazelbuild/bazel-skylib/releases/download/1.4.1/bazel-skylib-1.4.1.tar.gz",
         |    ],
         |)
         |
         |http_archive(
         |    name = "io_bazel_rules_scala",
         |    sha256 = "3b00fa0b243b04565abb17d3839a5f4fa6cc2cac571f6db9f83c1982ba1e19e5",
         |    strip_prefix = "rules_scala-6.5.0",
         |    url = "https://github.com/bazelbuild/rules_scala/releases/download/v6.5.0/rules_scala-v6.5.0.tar.gz",
         |)
         |
         |load("@io_bazel_rules_scala//:scala_config.bzl", "scala_config")
         |scala_config(scala_version = "2.12.18")
         |
         |load("@io_bazel_rules_scala//scala:scala.bzl", "rules_scala_setup", "rules_scala_toolchain_deps_repositories")
         |
         |rules_scala_setup()
         |rules_scala_toolchain_deps_repositories(fetch_sources = True)
         |
         |load("@rules_proto//proto:repositories.bzl", "rules_proto_dependencies", "rules_proto_toolchains")
         |rules_proto_dependencies()
         |rules_proto_toolchains()
         |
         |load("@io_bazel_rules_scala//scala:toolchains.bzl", "scala_register_toolchains")
         |scala_register_toolchains()
         |
         |register_toolchains(
         |    "//:semanticdb_toolchain",
         |)
         |
         |http_archive(
         |    name = "rules_jvm_external",
         |    sha256 = "6274687f6fc5783b589f56a2f1ed60de3ce1f99bc4e8f9edef3de43bdf7c6e74",
         |    strip_prefix = "rules_jvm_external-4.3",
         |    url = "https://github.com/bazelbuild/rules_jvm_external/archive/4.3.zip",
         |)
         |
         |load("@rules_jvm_external//:defs.bzl", "maven_install")
         |
         |maven_install(
         |    name = "maven",
         |    artifacts = [
         |        "com.typesafe.scala-logging:scala-logging_2.12:3.9.5",
         |    ],
         |    repositories = [
         |        "https://repo1.maven.org/maven2",
         |    ],
         |    fetch_sources = True
         |)
         |""".stripMargin

    val buildFiles =
      s"""|/BUILD
          |load("@io_bazel_rules_scala//scala:scala.bzl", "scala_library")
          |load("@io_bazel_rules_scala//scala:scala_toolchain.bzl", "scala_toolchain")
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
          |    name = "calculator",
          |    srcs = glob(["Main.scala"]),
          |    visibility = ["//visibility:public"],
          |    deps = [
          |        "@maven//:com_typesafe_scala_logging_scala_logging_2_12"
          |    ],
          |)
          |
          |""".stripMargin

    val sourceFiles =
      s"""
         |/Main.scala
         |import com.typesafe.scalalogging.Logger
         |
         |class Calculator {
         |  val logger = Logger("SimpleLogger")
         |
         |  def add(a: Int, b: Int): Int = a + b
         |  def subtract(a: Int, b: Int): Int = a - b
         |  def multiply(a: Int, b: Int): Int = a * b
         |}
         |
         |""".stripMargin

    val layout =
      s"""$bazelVersion
         |$workspaceFile
         |$buildFiles
         |$sourceFiles
         |""".stripMargin

    for {
      _ <- initialize(layout)
      _ <- server.didOpen("Main.scala")
      _ = server.workspaceDefinitions

      loggerDefinition <- server.definition(
        "Main.scala",
        """import org.joda.time.Instant
          |import com.typesafe.scalalogging.Log@@ger
          |
          |class Calculator {
          |  val instant = new Instant()
          |  val logger = Logger("SimpleLogger")
          |
          |  def add(a: Int, b: Int): Int = a + b
          |  def subtract(a: Int, b: Int): Int = a - b
          |  def multiply(a: Int, b: Int): Int = a * b
          |}""".stripMargin,
        workspace,
      )

      _ = assert(
        loggerDefinition.nonEmpty,
        s"Expected a definition location for 'Logger', but got an empty result.",
      )
      _ = assert(
        loggerDefinition.head.getUri.contains("scala-logging"),
        s"Expected logger definition URI to contain 'scala-logging', but was: ${loggerDefinition.head.getUri}",
      )
    } yield ()
  }

  private val commonCode =
    """|scala_library(
       |    name = "hello_lib",
       |    srcs = ["Hello.scala", "Bar.scala"],
       |)
       |
       |scala_binary(
       |    name = "hello",
       |    srcs = ["Main.scala", "Decode.scala"],
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
       |/Decode.scala
       |class Decode {
       | def decoded = this
       |}
       |
       |object Decode {
       | def decode: String = "decode"
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

  private val moduleWorkspaceLayout =
    s"""|/BUILD
        |load("@rules_scala//scala:scala.bzl", "scala_binary", "scala_library")
        |load("@rules_scala//scala:scala_toolchain.bzl", "scala_toolchain")
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
        |    toolchain_type = "@scala_rules//scala:toolchain_type",
        |    visibility = ["//visibility:public"],
        |)
        |
        |$commonCode
        |""".stripMargin

}
