package tests

import scala.concurrent.Future
import scala.util.Properties

import scala.meta.internal.builds.BuildTool
import scala.meta.internal.metals.Debug
import scala.meta.internal.metals.Messages.ImportBuild
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ServerCommands

import ch.epfl.scala.bsp4j.BspConnectionDetails
import com.google.gson.Gson
import org.eclipse.lsp4j.MessageActionItem

trait BuildServerInitializer {
  def initialize(layout: String, expectError: Boolean = false): Future[Unit]
}

trait QuickBuildInitializer extends BuildServerInitializer {
  this: BaseLspSuite =>
  override def initialize(
      layout: String,
      expectError: Boolean = false
  ): Future[Unit] = {
    Debug.printEnclosing()
    writeLayout(layout)
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

trait BloopImportInitializer extends BuildServerInitializer {
  this: BaseLspSuite =>
  override def initialize(
      layout: String,
      expectError: Boolean = false
  ): Future[Unit] = {
    writeLayout(layout)
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

trait SbtServerInitializer extends BuildServerInitializer {
  this: BaseLspSuite =>
  override def initialize(
      layout: String,
      expectError: Boolean = false
  ): Future[Unit] = {
    writeLayout(layout)
    generateBspConig()
    for {
      _ <- server.initialize()
      _ <- server.initialized()
      // choose sbt as the Bsp Server
      _ = client.selectBspServer = { _ => new MessageActionItem("sbt") }
      _ <- server.executeCommand(ServerCommands.BspSwitch.id, "sbt")
    } yield {
      if (!expectError) {
        server.assertBuildServerConnection()
      }
    }
  }

  private def generateBspConig(): Unit = {
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
        "1.5.5",
        "2.0.0-M5",
        List("scala").asJava
      )
      val gson = new Gson()
      sbtJson.writeText(gson.toJson(connectionDetails))
    }
  }
}
