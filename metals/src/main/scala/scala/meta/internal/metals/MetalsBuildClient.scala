package scala.meta.internal.metals

import ch.epfl.scala.bsp4j._
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.scalasbt.ipcsocket.UnixDomainSocket
import scala.meta.io.AbsolutePath
import scala.sys.process.Process
import scala.sys.process.ProcessLogger
import scala.util.Random

class MetalsBuildClient extends BuildClient {
  private var buildServer: Option[BuildServer] = None
  override def onBuildShowMessage(params: ShowMessageParams): Unit =
    scribe.info(params.toString)
  override def onBuildLogMessage(params: LogMessageParams): Unit =
    scribe.info(params.toString)
  override def onBuildPublishDiagnostics(
      params: PublishDiagnosticsParams
  ): Unit = scribe.info(params.toString)
  override def onBuildTargetDidChange(params: DidChangeBuildTarget): Unit =
    scribe.info(params.toString)
  override def buildRegisterFileWatcher(
      params: RegisterFileWatcherParams
  ): CompletableFuture[RegisterFileWatcherResult] = {
    scribe.info(params.toString)
    null
  }
  override def buildCancelFileWatcher(
      params: CancelFileWatcherParams
  ): CompletableFuture[CancelFileWatcherResult] = {
    scribe.info(params.toString)
    null
  }
  override def onBuildTargetCompileReport(params: CompileReport): Unit =
    scribe.info(params.toString)

  def connect(server: BuildServer): Unit = {
    this.buildServer = Some(server)
  }
}

trait MetalsBuildServer extends BuildServer with ScalaBuildServer

case class BuildServerConnection(
    client: MetalsBuildClient,
    server: MetalsBuildServer,
    cancelables: List[Cancelable]
)

object BuildServerConnection {
  def connect(workspace: AbsolutePath): BuildServerConnection = {
    val tmp = Files.createTempDirectory("bsp")
    val id = java.lang.Long.toString(Random.nextLong(), Character.MAX_RADIX)
    val socket = tmp.resolve(s"$id.socket")
    socket.toFile.deleteOnExit()
    val args = List[String](
      "bloop",
      "bsp",
      "--protocol",
      "local",
      "--socket",
      socket.toString
    )
    val logger = MetalsLogger.newBspLogger(workspace)
    val bspProcess = Process(
      args,
      cwd = workspace.toFile
    ).run(
      ProcessLogger(
        out => logger.info(out),
        err => logger.error(err)
      )
    )
    waitForFileToBeCreated(socket, retryDelayMillis = 100, maxRetries = 20)
    val bloop = new UnixDomainSocket(socket.toFile.getCanonicalPath)
    val executorService = Executors.newCachedThreadPool()
    val localClient = new MetalsBuildClient
    val tracePrinter = GlobalLogging.setupTracePrinter("BSP")
    val launcher = new Launcher.Builder[MetalsBuildServer]()
      .traceMessages(tracePrinter)
      .setRemoteInterface(classOf[MetalsBuildServer])
      .setExecutorService(executorService)
      .setInput(bloop.getInputStream)
      .setOutput(bloop.getOutputStream)
      .setLocalService(localClient)
      .create()
    launcher.startListening()
    val remoteServer = launcher.getRemoteProxy
    localClient.onConnectWithServer(remoteServer)
    val cancelables = List(
      Cancelable(() => bloop.close()),
      Cancelable(() => bspProcess.destroy()),
      Cancelable(() => executorService.shutdown()),
    )
    BuildServerConnection(localClient, remoteServer, cancelables)
  }

  private def waitForFileToBeCreated(
      path: Path,
      retryDelayMillis: Long,
      maxRetries: Int
  ): Unit = {
    if (maxRetries > 0) {
      if (Files.exists(path)) ()
      else {
        Thread.sleep(retryDelayMillis)
        waitForFileToBeCreated(path, retryDelayMillis, maxRetries - 1)
      }
    } else {
      sys.error(s"unable to establish connection with bloop, no file: $path")
    }
  }
}
