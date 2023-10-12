package scala.meta.internal.metals.scalacli

import java.io.File
import java.util.concurrent.atomic.AtomicReference

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
import scala.meta.internal.metals.StatusBar
import scala.meta.internal.metals.Tables
import scala.meta.internal.metals.TargetData
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.process.SystemProcess
import scala.meta.io.AbsolutePath

class ScalaCliServers(
    compilers: () => Compilers,
    compilations: Compilations,
    statusBar: () => StatusBar,
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
)(implicit ec: ExecutionContextExecutorService)
    extends Cancelable {
  private val serversRef: AtomicReference[Set[ScalaCli]] = new AtomicReference(
    Set.empty
  )

  private lazy val localScalaCli: Option[Seq[String]] =
    ScalaCli.localScalaCli(userConfig())

  def servers: List[ScalaCli] = serversRef.get().toList

  def setupIDE(path: AbsolutePath): Future[Unit] = {
    localScalaCli
      .map { cliCommand =>
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
  }.toList

  def jvmBased(): Seq[String] = {
    val cp = ScalaCli.scalaCliClassPath()
    Seq(
      ScalaCli.javaCommand,
      "-cp",
      cp.mkString(File.pathSeparator),
      ScalaCli.scalaCliMainClass,
    )
  }

  def lastImportedBuilds: List[(ImportedBuild, TargetData)] =
    servers
      .map(server => (server.lastImportedBuild, server.buildTargetsData))
      .toList

  def buildServers: List[BuildServerConnection] = servers.flatMap(_.buildServer)

  def cancel(): Unit = {
    val servers = serversRef.getAndSet(Set.empty)
    servers.foreach(_.cancel())
  }

  def loaded(path: AbsolutePath): Boolean =
    servers.exists(_.path.toNIO.startsWith(path.toNIO))

  def paths: List[AbsolutePath] = servers.map(_.path)

  def start(path: AbsolutePath): Future[Unit] = {
    val scalaCli =
      new ScalaCli(
        compilers,
        compilations,
        statusBar,
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
      )

    val prevServers = serversRef.getAndUpdate { servers =>
      if (servers.exists(_.path == path)) servers
      else servers + scalaCli
    }

    prevServers
      .find(_.path == path)
      .getOrElse {
        buildTargets.addData(scalaCli.buildTargetsData)
        scalaCli
      }
      .start()
  }

  def stop(): Future[Unit] = {
    val servers = serversRef.getAndSet(Set.empty)
    Future.sequence(servers.map(_.stop())).ignoreValue
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

}
