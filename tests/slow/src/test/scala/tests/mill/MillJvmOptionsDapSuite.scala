package tests.mill

import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.MetalsServerConfig

import tests.BaseDapSuite
import tests.BaseMillServerSuite
import tests.MillBuildLayout
import tests.MillServerInitializer

// Build servers without a BSP debug provider (e.g. Mill) use the in-house debug
// adapter, which must forward `jvmRunEnvironment` JVM options to the debuggee.
class MillJvmOptionsDapSuite
    extends BaseDapSuite(
      "mill-debug-jvm-options",
      MillServerInitializer,
      MillBuildLayout,
    )
    with BaseMillServerSuite {

  override def afterEach(context: AfterEach): Unit = {
    super.afterEach(context)
    killMillServer(workspace)
  }

  // mill sometimes hangs and doesn't return main classes
  override protected val retryTimes: Int = 2

  // otherwise we get both Scala 2.12 and 2.13 dependencies, which is more tricky
  override def scalaVersion: String = BuildInfo.scala212

  override def serverConfig: MetalsServerConfig =
    super.serverConfig.copy(debugServerStartTimeout = 360)

  test("build-server-jvm-options-reach-debuggee") {
    cleanWorkspace()
    // `forkArgs` is only known to the build server; the launched main class
    // carries no jvm options, so the property can only be set if the build
    // server's jvm options are forwarded to the forked JVM.
    for {
      _ <- initialize(
        s"""|/build.mill
            |//| mill-jvm-version: system
            |//| mill-version: ${BuildInfo.millVersion}
            |package build
            |import mill.*, scalalib.*
            |
            |object a extends ScalaModule {
            |  def scalaVersion = "$scalaVersion"
            |  def forkArgs = Seq("-Dmetals.fromBuildServer=hello")
            |}
            |/a/src/Main.scala
            |package a
            |object Main {
            |  def main(args: Array[String]): Unit = {
            |    print(System.getProperty("metals.fromBuildServer"))
            |    System.exit(0)
            |  }
            |}
            |""".stripMargin
      )
      debugger <- debugMain("a", "a.Main")
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assertNoDiff(output, "hello")
  }
}
