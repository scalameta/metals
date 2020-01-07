package tests.pants

import scala.meta.internal.builds.{PantsBuildTool, PantsDigest}
import scala.meta.internal.metals.UserConfiguration
import scala.meta.io.AbsolutePath
import tests.BaseImportSuite
import scala.meta.internal.builds.BuildTool
import tests.FileLayout
import java.nio.file.Files
import scala.meta.internal.metals.BuildInfo
import java.nio.file.StandardOpenOption
import scala.util.control.NonFatal
import scala.sys.process._
import funsuite.BeforeEach
import funsuite.AfterEach

object PantsLspSuite extends BaseImportSuite("pants") {

  val buildTool: PantsBuildTool = PantsBuildTool(() => userConfig)

  override def afterEach(context: AfterEach): Unit = {
    try {
      List(workspace.resolve("pants").toString(), "clean-all", "--async").!
      super.afterEach(context)
    } catch {
      case NonFatal(_) =>
    }
  }

  override def beforeEach(context: BeforeEach): Unit = {
    super.beforeEach(context)
    installPants()
  }

  override def currentDigest(
      workspace: AbsolutePath
  ): Option[String] = {
    new PantsDigest(
      () => UserConfiguration(pantsTargets = Option(List("src::")))
    ).current(workspace)
  }

  private def preInitialized = {
    val pants_targets_config =
      s"""
         |{
         |  "pants-targets": "src::"
         |}
         |""".stripMargin
    server.didChangeConfiguration(pants_targets_config)
  }

  def installPants(): Unit = {
    cleanWorkspace()
    val pants = BuildTool.copyFromResource(workspace.toNIO, "pants")
    pants.toFile().setExecutable(true)
    val exit = List(pants.toString(), "generate-pants-ini").!
    require(exit == 0, "failed to generate pants.ini")
    Files.write(
      workspace.resolve("pants.ini").toNIO,
      """|[scala]
         |version: custom
         |suffix_version: 2.12
         |strict_deps: False
         |scala_repl: //:scala-repl
         |""".stripMargin.getBytes(),
      StandardOpenOption.APPEND
    )
    Files.write(
      workspace.resolve("BUILD.tools").toNIO,
      s"""|SCALA_VERSION='${BuildInfo.scala212}'
          |jar_library(
          |  name = 'scalac',
          |  jars = [
          |    jar(org = 'org.scala-lang', name = 'scala-compiler', rev = SCALA_VERSION),
          |  ],
          |  dependencies=[
          |    ':scala-reflect',
          |    ':scala-library',
          |  ])
          |jar_library(name = 'scala-library', jars = [jar(org = 'org.scala-lang', name = 'scala-library', rev = SCALA_VERSION)], scope='force')
          |jar_library(name = 'scala-reflect', jars = [jar(org = 'org.scala-lang', name = 'scala-reflect', rev = SCALA_VERSION, intransitive=True)])
          |target(name = 'scala-repl', dependencies=[ ':scalac', ':scala-reflect', ':scala-library'])
          |""".stripMargin.getBytes()
    )
  }

  // TODO(olafur) re-enable this test when it's no longer flaky
  // https://github.com/scalameta/metals/issues/1182
  // testAsync("basic") {
  //   for {
  //     _ <- server.initialize(
  //       s"""
  //          |/src/BUILD
  //          |scala_library(
  //          |  name='math',
  //          |  sources=globs('*.scala'),
  //          |)
  //          |/src/Util.scala
  //          |package src
  //          |object Util {
  //          |  def add(a: Int, b: Int) = a + b
  //          |}
  //          |/src/Math.scala
  //          |package src
  //          |class Math {
  //          |  def add(a: Int, b: Int): Unit = a + b
  //          |}
  //          |""".stripMargin,
  //       preInitialized = () => preInitialized
  //     )
  //     _ = assertNoDiff(
  //       client.workspaceMessageRequests,
  //       List(
  //         importBuildMessage,
  //         progressMessage
  //       ).mkString("\n")
  //     )
  //     _ <- server.didOpen("src/Math.scala")
  //     _ = client.messageRequests.clear() // restart
  //     _ <- server.didSave(s"src/BUILD") { text =>
  //       text.replace("'math'", "'math1'")
  //     }
  //     _ <- server.didOpen("src/Util.scala")
  //     _ = assertNoDiff(
  //       client.workspaceMessageRequests,
  //       List(
  //         importBuildChangesMessage,
  //         progressMessage
  //       ).mkString("\n")
  //     )
  //   } yield ()
  // }

  testAsync("regenerate") {
    for {
      _ <- server.initialize(
        s"""
           |/src/BUILD
           |scala_library(
           |  name='math',
           |  sources=globs('*.scala'),
           |)
           |/src/Util.scala
           |package src
           |object Util {
           |  def add(a: Int, b: Int) = a + b
           |}
           |""".stripMargin,
        preInitialized = () => preInitialized
      )
      _ <- server.didOpen("src/Util.scala")
      _ = assertNoDiagnostics()
      _ = FileLayout.fromString(
        """
          |/src/Example.scala
          |package src
          |object Example {
          |  def number = Util.add(1, 2)
          |  def error: Int = ""
          |}
          |""".stripMargin,
        workspace
      )
      _ <- server.didOpen("src/Example.scala")
      _ <- server.didSave("src/Example.scala")(identity)
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|src/Example.scala:4:20: error: type mismatch;
           | found   : String("")
           | required: Int
           |  def error: Int = ""
           |                   ^^
           |""".stripMargin
      )
    } yield ()
  }

  testAsync("binary-dependency") {
    for {
      _ <- server.initialize(
        s"""
           |/core/BUILD
           |scala_library(
           |  sources=globs('*.scala'),
           |)
           |/core/Lib.scala
           |package core
           |object Lib {
           |  def greeting = "Hello from lib!"
           |}
           |/src/BUILD
           |scala_library(
           |  sources=globs('*.scala'),
           |  dependencies=['core:core']
           |)
           |/src/Main.scala
           |package src
           |object Main extends App {
           |  println(core.Lib.greeting)
           |}
           |""".stripMargin,
        preInitialized = () => preInitialized
      )
      _ <- server.didOpen("src/Main.scala")
      _ = assertNoDiagnostics() // "core" was compiled during import
      _ <- server.didOpen("core/Lib.scala")
      _ <- server.didSave("core/Lib.scala")(
        _.replaceAllLiterally("greeting", "greeting: Int")
      )
      _ = assertNoDiagnostics() // no errors because "core" is not exported.
    } yield ()
  }
}
