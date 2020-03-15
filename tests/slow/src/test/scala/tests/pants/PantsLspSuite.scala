package tests.pants

import scala.meta.internal.builds.{PantsBuildTool, PantsDigest}
import scala.meta.internal.metals.UserConfiguration
import scala.meta.io.AbsolutePath
import tests.BaseImportSuite
import scala.meta.internal.builds.BuildTool
import tests.FileLayout
import scala.meta.internal.metals.BuildInfo
import scala.util.control.NonFatal
import scala.sys.process._

class PantsLspSuite extends BaseImportSuite("pants") {

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
    new PantsDigest(() =>
      UserConfiguration(pantsTargets = Option(List("src::")))
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
    FileLayout.fromString(
      s"""|/BUILD.tools
          |SCALA_VERSION='${BuildInfo.scala212}'
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
          |
          |/pants.ini
          |[GLOBAL]
          |pants_version: 1.24.0rc1
          |[scala]
          |version: custom
          |suffix_version: 2.12
          |strict_deps: False
          |scala_repl: //:scala-repl
          |""".stripMargin,
      root = workspace
    )
  }

  test("basic".flaky) {
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
           |/src/Math.scala
           |package src
           |class Math {
           |  def add(a: Int, b: Int): Unit = a + b
           |}
           |""".stripMargin,
        preInitialized = () => preInitialized
      )
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        List(
          importBuildMessage,
          progressMessage
        ).mkString("\n")
      )
      _ <- server.didOpen("src/Math.scala")
      _ = client.messageRequests.clear() // restart
      _ <- server.didSave(s"src/BUILD") { text =>
        text.replace("'math'", "'math1'")
      }
      _ <- server.didOpen("src/Util.scala")
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        List(
          importBuildChangesMessage,
          progressMessage
        ).mkString("\n")
      )
    } yield ()
  }

  test("binary-dependency") {
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
