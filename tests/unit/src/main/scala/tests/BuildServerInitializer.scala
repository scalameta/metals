package tests

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Properties

import scala.meta.internal.builds.BuildTool
import scala.meta.internal.builds.SbtBuildTool
import scala.meta.internal.metals.Directories
import scala.meta.internal.metals.Messages.GenerateBspAndConnect
import scala.meta.internal.metals.Messages.ImportBuild
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BspConnectionDetails
import com.google.gson.Gson
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.MessageActionItem

sealed trait BuildServerInitializer {
  def initialize(
      workspace: AbsolutePath,
      server: TestingServer,
      client: TestingClient,
      expectError: Boolean,
      userConfig: UserConfiguration,
      workspaceFolders: Option[List[String]] = None,
  )(implicit ec: ExecutionContext): Future[InitializeResult]
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
      expectError: Boolean,
      userConfig: UserConfiguration,
      workspaceFolders: Option[List[String]] = None,
  )(implicit ec: ExecutionContext): Future[InitializeResult] = {
    val foldersToInit =
      workspaceFolders match {
        case Some(workspaceFolders) => workspaceFolders.map(workspace.resolve)
        case None => List(workspace)
      }
    scribe.info(s"Initializing folders with Bloop: $foldersToInit")
    foldersToInit.foreach(QuickBuild.bloopInstall)
    for {
      initializeResult <- server.initialize(workspaceFolders)
      _ <- server.initialized()
      _ <- server.didChangeConfiguration(userConfig.toString)
    } yield {
      if (!expectError) {
        server.assertBuildServerConnection()
      }
      initializeResult
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
      expectError: Boolean,
      userConfig: UserConfiguration,
      workspaceFolders: Option[List[String]] = None,
  )(implicit ec: ExecutionContext): Future[InitializeResult] = {
    for {
      initializeResult <- server.initialize(workspaceFolders)
      // Import build using Bloop
      _ = client.importBuild = ImportBuild.yes
      _ <- server.initialized()
      _ <- server.didChangeConfiguration(userConfig.toString())
    } yield {
      if (!expectError) {
        server.assertBuildServerConnection()
      }
      initializeResult
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
      expectError: Boolean,
      userConfig: UserConfiguration,
      workspaceFolders: Option[List[String]] = None,
  )(implicit ec: ExecutionContext): Future[InitializeResult] = {
    val paths = workspaceFolders match {
      case Some(workspaceFolders) => workspaceFolders.map(workspace.resolve)
      case None => List(workspace)
    }
    paths.foreach { path =>
      val sbtVersion =
        SbtBuildTool
          .loadVersion(path)
          .getOrElse(V.sbtVersion)
      generateBspConfig(path, sbtVersion)
    }
    for {
      initializeResult <- server.initialize(workspaceFolders)
      _ <- server.initialized()
      // choose sbt as the Bsp Server
      _ = client.selectBspServer = { items =>
        items.find(_.getTitle().contains("sbt")).getOrElse {
          throw new RuntimeException(
            "sbt was expected in the test, but not found"
          )
        }
      }
      _ <- server.didChangeConfiguration(userConfig.toString)
      _ <- paths.zipWithIndex.foldLeft(Future.successful(())) {
        case (future, (_, i)) =>
          future.flatMap { _ =>
            client.chooseWorkspaceFolder = { _(i) }
            server.executeCommand(ServerCommands.BspSwitch).ignoreValue
          }
      }
    } yield {
      if (!expectError) {
        server.assertBuildServerConnection()
      }
      initializeResult
    }
  }

  def generateBspConfig(
      workspace: AbsolutePath,
      sbtVersion: String,
  ): Unit = {
    val bspFolder = workspace.resolve(Directories.bsp)
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
      )
      val connectionDetails = new BspConnectionDetails(
        "sbt",
        argv.asJava,
        sbtVersion,
        "2.0.0-M5",
        List("scala").asJava,
      )
      val gson = new Gson()
      sbtJson.writeText(gson.toJson(connectionDetails))
    }
  }
}

object MillServerInitializer extends BuildServerInitializer {
  this: BaseLspSuite =>
  override def initialize(
      workspace: AbsolutePath,
      server: TestingServer,
      client: TestingClient,
      expectError: Boolean,
      userConfig: UserConfiguration,
      workspaceFolders: Option[List[String]] = None,
  )(implicit ec: ExecutionContext): Future[InitializeResult] = {
    for {
      initializeResult <- server.initialize()
      _ <- server.initialized()
      _ <- server.didChangeConfiguration(userConfig.toString)
      // choose mill-bsp as the Bsp Server
      _ = client.selectBspServer = { _ => new MessageActionItem("mill-bsp") }
      _ <- server.executeCommand(ServerCommands.BspSwitch)
    } yield {
      if (!expectError) {
        server.assertBuildServerConnection()
      }
      initializeResult
    }
  }
}

object BazelServerInitializer extends BuildServerInitializer {
  this: BaseLspSuite =>
  override def initialize(
      workspace: AbsolutePath,
      server: TestingServer,
      client: TestingClient,
      expectError: Boolean,
      userConfig: UserConfiguration,
      workspaceFolders: Option[List[String]] = None,
  )(implicit ec: ExecutionContext): Future[InitializeResult] = {
    for {
      initializeResult <- server.initialize()
      // Import build using Bazel
      _ = client.generateBspAndConnect = GenerateBspAndConnect.yes
      _ <- server.initialized()
      _ <- server.didChangeConfiguration(userConfig.toString)
    } yield {
      if (!expectError) {
        server.assertBuildServerConnection()
      }
      initializeResult
    }
  }
}
