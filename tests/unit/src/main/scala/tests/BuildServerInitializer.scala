package tests

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Properties

import scala.meta.internal.builds.BuildTool
import scala.meta.internal.builds.SbtBuildTool
import scala.meta.internal.metals.Messages.ImportBuild
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BspConnectionDetails
import com.google.gson.Gson
import org.eclipse.lsp4j.MessageActionItem

sealed trait BuildServerInitializer {
  def initialize(
      workspace: AbsolutePath,
      server: TestingServer,
      client: TestingClient,
      layout: String,
      expectError: Boolean
  )(implicit ec: ExecutionContext): Future[Unit]
}

/**
 * Set up your workspace using QuickBuild as your build tool.
 * This will take your `metals.json` file and quickly produce `.bloop/` files from it.
 */
object QuickBuildInitializer extends BuildServerInitializer {
  this: BaseLspSuite =>
  override def initialize(
      workspace: AbsolutePath,
      server: TestingServer,
      client: TestingClient,
      layout: String,
      expectError: Boolean
  )(implicit ec: ExecutionContext): Future[Unit] = {
    QuickBuild.bloopInstall(workspace)
    for {
      _ <- server.initialize()
      _ <- server.initialized()
    } yield {
      if (!expectError) {
        server.assertBuildServerConnection()
      }
    }
  }
}

/**
 * Set up your workspace by responding to an Import Build request which will
 * run Bloop Install via the build tool being used.
 */
object BloopImportInitializer extends BuildServerInitializer {
  this: BaseLspSuite =>
  override def initialize(
      workspace: AbsolutePath,
      server: TestingServer,
      client: TestingClient,
      layout: String,
      expectError: Boolean
  )(implicit ec: ExecutionContext): Future[Unit] = {
    for {
      _ <- server.initialize()
      // Import build using Bloop
      _ = client.importBuild = ImportBuild.yes
      _ <- server.initialized()
    } yield {
      if (!expectError) {
        server.assertBuildServerConnection()
      }
    }
  }
}

/**
 * Assumes sbt is being used as a build tool and also for your BSP server.
 * This generates the .bsp/sbt.json file and invoke the BSP switch command
 * with sbt as the build server.
 */
object SbtServerInitializer extends BuildServerInitializer {
  this: BaseLspSuite =>
  override def initialize(
      workspace: AbsolutePath,
      server: TestingServer,
      client: TestingClient,
      layout: String,
      expectError: Boolean
  )(implicit ec: ExecutionContext): Future[Unit] = {
    val sbtVersion =
      SbtBuildTool
        .loadVersion(workspace)
        .getOrElse(V.sbtVersion)
    generateBspConfig(workspace, sbtVersion)
    for {
      _ <- server.initialize()
      _ <- server.initialized()
      // choose sbt as the Bsp Server
      _ = client.selectBspServer = { _ => new MessageActionItem("sbt") }
      _ <- server.executeCommand(ServerCommands.BspSwitch.id)
    } yield {
      if (!expectError) {
        server.assertBuildServerConnection()
      }
    }
  }

  private def generateBspConfig(
      workspace: AbsolutePath,
      sbtVersion: String
  ): Unit = {
    val bspFolder = workspace.resolve(".bsp")
    val sbtJson = bspFolder.resolve("sbt.json")
    // don't overwrite existing BSP config
    if (!sbtJson.isFile) {
      // we create bsp/sbt.json file manually because `sbt bspConfig` takes too long
      val sbtLaunchJar =
        BuildTool.copyFromResource(bspFolder.toNIO, "sbt-launch.jar")
      val argv = List(
        s"${Properties.javaHome}/bin/java",
        "-Xms100m",
        "-Xmx100m",
        "-classpath",
        sbtLaunchJar.toString,
        "xsbt.boot.Boot",
        "-bsp",
        s"--sbt-launch-jar=$sbtLaunchJar"
      )
      val connectionDetails = new BspConnectionDetails(
        "sbt",
        argv.asJava,
        sbtVersion,
        "2.0.0-M5",
        List("scala").asJava
      )
      val gson = new Gson()
      sbtJson.writeText(gson.toJson(connectionDetails))
    }
  }
}
