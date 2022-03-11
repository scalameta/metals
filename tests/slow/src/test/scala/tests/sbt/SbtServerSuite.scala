package tests.sbt

import scala.meta.internal.builds.SbtBuildTool
import scala.meta.internal.builds.SbtDigest
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.Messages.ImportBuildChanges
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.DebugSessionParamsDataKind
import ch.epfl.scala.bsp4j.ScalaMainClass
import tests.BaseImportSuite
import tests.SbtBuildLayout
import tests.SbtServerInitializer

/**
 * Basic suite to ensure that a connection to sbt server can be made.
 */
class SbtServerSuite
    extends BaseImportSuite("sbt-server", SbtServerInitializer) {

  val preBspVersion = "1.3.13"
  val supportedMetaBuildVersion = "1.6.0-M1"
  val supportedBspVersion = V.sbtVersion
  val scalaVersion = V.scala213
  val buildTool: SbtBuildTool = SbtBuildTool(None, () => userConfig)

  override def currentDigest(
      workspace: AbsolutePath
  ): Option[String] = SbtDigest.current(workspace)

  test("too-old") {
    cleanWorkspace()
    writeLayout(
      s"""|/project/build.properties
          |sbt.version=$preBspVersion
          |/build.sbt
          |${SbtBuildLayout.commonSbtSettings}
          |scalaVersion := "${V.scala213}"
          |""".stripMargin
    )
    for {
      _ <- server.initialize()
      _ <- server.initialized()
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        List(
          importBuildMessage
        ).mkString("\n")
      )
      _ = client.messageRequests.clear()
      // Attempt to create a .bsp/sbt.json file
      _ <- server.executeCommand(ServerCommands.GenerateBspConfig)
    } yield {
      assertNoDiff(
        client.workspaceShowMessages,
        Messages.NoBspSupport.toString
      )
    }
  }

  test("generate") {
    def sbtLaunchJar = workspace.resolve(".bsp/sbt-launch.jar")
    def sbtBspConfig = workspace.resolve(".bsp/sbt.json")
    def isBspConfigValid =
      sbtBspConfig.readText.contains(sbtLaunchJar.toString())
    def sbtBspPlugin = workspace.resolve("project/metals.sbt")
    def sbtJdiPlugin = workspace.resolve("project/project/metals.sbt")
    cleanWorkspace()
    writeLayout(SbtBuildLayout("", V.scala213))
    for {
      _ <- server.initialize()
      _ <- server.initialized()
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        // Project has no .bloop directory so user is asked to "import via bloop"
        // since bloop is still the default
        importBuildMessage
      )
      _ = client.messageRequests.clear() // restart
      _ = assert(!sbtBspConfig.exists)
      // At this point, we want to use sbt server, so create the sbt.json file.
      _ <- server.executeCommand(ServerCommands.GenerateBspConfig)
    } yield {
      assert(sbtLaunchJar.exists)
      assert(isBspConfigValid)
      assert(sbtBspPlugin.exists)
      assert(sbtBspConfig.exists)
      assert(sbtJdiPlugin.exists)
      assert(sbtJdiPlugin.readText.contains("sbt-jdi-tools"))
    }
  }

  test("reload") {
    cleanWorkspace()
    for {
      _ <- initialize(SbtBuildLayout("", V.scala213))
      // reload build after build.sbt changes
      _ = client.importBuildChanges = ImportBuildChanges.yes
      _ <- server.didSave("build.sbt") { text =>
        s"""$text
           |ibraryDependencies += "com.lihaoyi" %% "sourcecode" % "0.1.4"
           |""".stripMargin
      }
      _ = {
        assertNoDiff(
          client.workspaceErrorShowMessages,
          Messages.ReloadProjectFailed.getMessage
        )
        client.showMessages.clear()
      }
      _ <- server.didSave("build.sbt") { _ =>
        s"""scalaVersion := "${V.scala213}"
           |libraryDependencies += "com.lihaoyi" %% "sourcecode" % "0.1.4"
           |""".stripMargin
      }
      _ = {
        assert(client.workspaceErrorShowMessages.isEmpty)
      }
    } yield ()
  }

  test("debug") {
    cleanWorkspace()
    val mainClass = new ScalaMainClass(
      "a.Main",
      List("Bar").asJava,
      List("-Dproperty=Foo").asJava
    )
    mainClass.setEnvironmentVariables(List("HELLO=Foo").asJava)
    for {
      _ <- initialize(
        s"""|/project/build.properties
            |sbt.version=${V.sbtVersion}
            |/build.sbt
            |import sbt.internal.bsp.BuildTargetIdentifier
            |import java.net.URI
            |${SbtBuildLayout.commonSbtSettings}
            |scalaVersion := "${V.scala213}"
            |Compile / bspTargetIdentifier := {
            |  BuildTargetIdentifier(new URI("debug"))
            |}
            |/src/main/scala/a/Main.scala
            |package a
            |object Main {
            |  def main(args: Array[String]) = {
            |    val foo = sys.props.getOrElse("property", "")
            |    val bar = args(0)
            |    val env = sys.env.get("HELLO")
            |    print(foo + bar)
            |    env.foreach(print)
            |    System.exit(0)
            |  }
            |}
            |""".stripMargin
      )
      debugger <- server.startDebugging(
        "debug",
        DebugSessionParamsDataKind.SCALA_MAIN_CLASS,
        mainClass
      )
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assertNoDiff(output, "FooBarFoo")
  }

  // this test fails if semantidb is not enabled
  // or not correctly configured in the meta-build target.
  test("meta-build-target") {
    cleanWorkspace()
    val layout =
      s"""|/project/build.properties
          |sbt.version=$supportedMetaBuildVersion
          |/build.sbt
          |${SbtBuildLayout.commonSbtSettings}
          |""".stripMargin
    for {
      _ <- initialize(layout)
    } yield {
      // assert no misconfiguration message
      assertNoDiff(
        client.workspaceMessageRequests,
        List(
          importBuildMessage,
          Messages.BspSwitch.message
        ).mkString("\n")
      )
      // assert contains the meta-build-target-build
      assertNoDiff(
        server.server.buildTargets.all
          .map(_.getDisplayName())
          .toSeq
          .sorted
          .mkString("\n"),
        Seq(
          "meta-build-target",
          "meta-build-target-build",
          "meta-build-target-test"
        ).mkString("\n")
      )
    }
  }
}
