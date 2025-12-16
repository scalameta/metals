package scala.meta.internal.metals.scalacli

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference

import scala.collection.immutable.Queue
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future

import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.BuildServerConnection
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.Cancelable
import scala.meta.internal.metals.Compilations
import scala.meta.internal.metals.Compilers
import scala.meta.internal.metals.Diagnostics
import scala.meta.internal.metals.ImportedBuild
import scala.meta.internal.metals.MetalsBuildClient
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.Tables
import scala.meta.internal.metals.TargetData
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.WorkDoneProgress
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.scalacli.ScalaCli.ScalaCliCommand
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.internal.process.SystemProcess
import scala.meta.io.AbsolutePath

import coursier.core.Version

class ScalaCliServers(
    compilers: () => Compilers,
    compilations: Compilations,
    workDoneProgress: WorkDoneProgress,
    buffers: Buffers,
    indexWorkspace: () => Future[Unit],
    diagnostics: () => Diagnostics,
    tables: Tables,
    buildClient: () => MetalsBuildClient,
    languageClient: MetalsLanguageClient,
    config: () => MetalsServerConfig,
    userConfig: () => UserConfiguration,
    parseTreesAndPublishDiags: Seq[AbsolutePath] => Future[Unit],
    buildTargets: BuildTargets,
    maxServers: Int,
)(implicit ec: ExecutionContextExecutorService)
    extends Cancelable {

  private def localTmpWorkspace(path: AbsolutePath) = {
    val root = if (path.isDirectory) path else path.parent
    root.resolve(s".metals-scala-cli/")
  }
  private val scalaCliBuildDirectory =
    new AtomicReference[Option[AbsolutePath]](None)

  private val serversRef: AtomicReference[Queue[ScalaCli]] =
    new AtomicReference(Queue.empty)

  private lazy val localScalaCli: Option[ScalaCliCommand] =
    ScalaCli.localScalaCli(userConfig())

  def servers: Iterable[ScalaCli] = serversRef.get()

  def setupIDE(path: AbsolutePath): Future[Unit] = {
    localScalaCli
      .map { case ScalaCliCommand(cliCommand, _) =>
        val command = cliCommand ++ Seq("setup-ide", path.toString())
        scribe.info(s"Running $command")
        val proc = SystemProcess.run(
          command.toList,
          path,
          redirectErrorOutput = false,
          env = Map(),
          processOut = None,
          processErr = Some(line => scribe.info("Scala CLI: " + line)),
          discardInput = false,
          threadNamePrefix = "scala-cli-setup-ide",
        )
        proc.complete.ignoreValue
      }
      .getOrElse {
        start(path)
      }
  }

  private lazy val cliCommand = {
    localScalaCli.getOrElse {
      scribe.warn(
        s"scala-cli >= ${ScalaCli.minVersion} not found in PATH, fetching and starting a JVM-based Scala CLI"
      )
      jvmBased()
    }
  }

  def jvmBased(): ScalaCliCommand = {
    val cp = ScalaCli.scalaCliClassPath()
    val command = Seq(
      ScalaCli.javaCommand,
      "-cp",
      cp.mkString(File.pathSeparator),
      ScalaCli.scalaCliMainClass,
    )
    ScalaCliCommand(command, Version(V.scalaCliVersion))
  }

  def lastImportedBuilds: List[(ImportedBuild, TargetData)] =
    servers
      .map(server => (server.lastImportedBuild, server.buildTargetsData))
      .toList

  def buildServers: Iterable[BuildServerConnection] =
    servers.flatMap(_.buildServer)

  def cancel(): Unit = {
    val servers = serversRef.getAndSet(Queue.empty)
    servers.foreach(_.cancel())
    servers.foreach(_.customWorkspace.foreach(_.deleteRecursively))
  }

  def loaded(path: AbsolutePath): Boolean =
    servers.exists(_.path.toNIO.startsWith(path.toNIO))

  def loadedExactly(path: AbsolutePath): Boolean =
    servers.exists(_.path == path)

  def paths: Iterable[AbsolutePath] = servers.map(_.path)

  def start(path: AbsolutePath): Future[Unit] = {
    if (!userConfig().scalaCliEnabled) {
      Future.unit
    }
    val customWorkspace =
      if (path.isDirectory) None
      else
        Some {
          val globalTmpDir =
            scalaCliBuildDirectory.get() match {
              case Some(workspace) => workspace
              case None =>
                val tmpFile =
                  AbsolutePath(
                    Files.createTempDirectory(s"metals-scala-cli")
                  )
                val Some(workspace) =
                  scalaCliBuildDirectory.updateAndGet {
                    case None => Some(tmpFile)
                    case some => some
                  }
                workspace
            }

          // When path and workspace have different roots on Windows `scala-cli` throws an error,
          // so we fallback to creating a tmp dir relative to `path`.
          if (globalTmpDir.toNIO.getRoot() == path.toNIO.getRoot()) globalTmpDir
          else {
            val workspace = localTmpWorkspace(path)
            if (!workspace.exists) Files.createDirectory(workspace.toNIO)
            workspace
          }
        }
    val scalaCli =
      new ScalaCli(
        compilers,
        compilations,
        workDoneProgress,
        buffers,
        indexWorkspace,
        diagnostics,
        tables,
        buildClient,
        languageClient,
        config,
        cliCommand,
        parseTreesAndPublishDiags,
        path,
        customWorkspace,
      )

    val prevServers = serversRef.getAndUpdate { servers =>
      if (servers.exists(_.path == path)) servers
      else {
        if (servers.size == maxServers) servers.drop(1) :+ scalaCli
        else servers :+ scalaCli
      }
    }

    val (newServer, oldServer) =
      prevServers
        .find(_.path == path)
        .map((_, None))
        .getOrElse {
          buildTargets.addData(scalaCli.buildTargetsData)
          val oldServer =
            Option.when(servers.size == 10)(prevServers.dequeue._1)
          (scalaCli, oldServer)
        }

    for {
      _ <- oldServer.map(_.stop()).getOrElse(Future.unit)
      _ <- newServer.start()
    } yield ()
  }

  def stop(): Future[Unit] = {
    val servers = serversRef.getAndSet(Queue.empty)
    Future.sequence(servers.map(_.stop()).toSeq).ignoreValue
  }

  def stop(path: AbsolutePath): Future[Unit] = {
    val servers = serversRef.getAndUpdate(s => s.filterNot(_.path == path))
    servers
      .collectFirst {
        case s if s.path == path =>
          buildTargets.removeData(s.buildTargetsData)
          s.stop()
      }
      .getOrElse(Future.successful(()))
  }

  /**
   * We reorder scala-cli servers on `didFocus`,
   * so the least recently used will be discarded first.
   */
  def didFocus(path: AbsolutePath): Unit =
    serversRef.getAndUpdate { servers =>
      servers.find(server => path.startWith(server.path)) match {
        case Some(foundServer) =>
          servers.filterNot(_ == foundServer) :+ foundServer
        case None => servers
      }
    }

}
