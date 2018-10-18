package scala.meta.internal.metals

import java.nio.file.Files
import java.util.concurrent.Executors
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.services.LanguageClient
import org.scalasbt.ipcsocket.UnixDomainSocket
import scala.language.reflectiveCalls
import scala.meta.io.AbsolutePath
import scala.sys.process.Process
import scala.sys.process.ProcessLogger
import scala.util.Random
import scala.util.control.NonFatal

object BloopServer {
  def connect(
      workspace: AbsolutePath,
      languageClient: LanguageClient,
      buildClient: MetalsBuildClient,
      classLoader: ClassLoader = this.getClass.getClassLoader
  ): Option[BuildServerConnection] = {
    val (socket, bloopCancelables) = startBloopBsp(workspace, classLoader)
    val bloop = new UnixDomainSocket(socket.toFile.getCanonicalPath)
    val executorService = Executors.newCachedThreadPool()
    val tracePrinter = GlobalLogging.setupTracePrinter("BSP")
    val launcher = new Launcher.Builder[MetalsBuildServer]()
      .traceMessages(tracePrinter)
      .setRemoteInterface(classOf[MetalsBuildServer])
      .setExecutorService(executorService)
      .setInput(bloop.getInputStream)
      .setOutput(bloop.getOutputStream)
      .setLocalService(buildClient)
      .create()
    val listening = launcher.startListening()
    val remoteServer = launcher.getRemoteProxy
    buildClient.onConnect(remoteServer)
    val cancelables = bloopCancelables ++ List(
      Cancelable(() => listening.cancel(true)),
      Cancelable(() => bloop.close()),
      Cancelable(() => executorService.shutdown()),
    )
    val connection =
      BuildServerConnection(buildClient, remoteServer, cancelables)
    Some(connection)
  }

  private def newSocketFile(): AbsolutePath = {
    val tmp = Files.createTempDirectory("bsp")
    val id = java.lang.Long.toString(Random.nextLong(), Character.MAX_RADIX)
    val socket = tmp.resolve(s"$id.socket")
    socket.toFile.deleteOnExit()
    AbsolutePath(socket)
  }

  private def startBloopBsp(
      workspace: AbsolutePath,
      classLoader: ClassLoader
  ): (AbsolutePath, List[Cancelable]) = {
    val socket = newSocketFile()
    val args = Array(
      "bsp",
      "--protocol",
      "local",
      "--socket",
      socket.toString
    )
    val logger = MetalsLogger.newBspLogger(workspace)
    val result: List[Cancelable] =
      if (bloopIsInstalled(workspace)) {
        pprint.log(
          Process(List("bloop", "projects"), cwd = workspace.toFile).!!
        )
        pprint.log(args)
        val bspProcess = Process(
          "bloop" +: args,
          cwd = workspace.toFile
        ).run(
          ProcessLogger(
            out => logger.info(out),
            err => logger.error(err)
          )
        )
        List(
          Cancelable(() => bspProcess.destroy())
        )
      } else {
        ???
      }
    waitForFileToBeCreated(socket, retryDelayMillis = 100, maxRetries = 40)
    (socket, result)
  }

  private def bloopIsInstalled(workspace: AbsolutePath): Boolean = {
    try {
      val output = Process(List("bloop", "help"), cwd = workspace.toFile).!!
      pprint.log(output)
      output.contains("Usage: bloop")
    } catch {
      case NonFatal(_) =>
        false
    }
  }

  private def waitForFileToBeCreated(
      socket: AbsolutePath,
      retryDelayMillis: Long,
      maxRetries: Int
  ): Unit = {
    if (maxRetries > 0) {
      if (Files.exists(socket.toNIO)) ()
      else {
        Thread.sleep(retryDelayMillis)
        waitForFileToBeCreated(socket, retryDelayMillis, maxRetries - 1)
      }
    } else {
      sys.error(s"unable to establish connection with bloop, no file: $socket")
    }
  }

  private def sbtPluginFile: String =
    """|val bloopVersion = "1.0.0"
       |val bloopModule = "ch.epfl.scala" % "sbt-bloop" % bloopVersion
       |libraryDependencies := {
       |  import Defaults.sbtPluginExtra
       |  val oldDependencies = libraryDependencies.value
       |  val bloopArtifacts =
       |    oldDependencies.filter(d => d.organization == "ch.epfl.scala" && d.name == "sbt-bloop")
       |
       |  // Only add the plugin if it cannot be found in the current library dependencies
       |  if (!bloopArtifacts.isEmpty) oldDependencies
       |  else {
       |    val sbtVersion = (Keys.sbtBinaryVersion in pluginCrossBuild).value
       |    val scalaVersion = (Keys.scalaBinaryVersion in update).value
       |    val bloopPlugin = sbtPluginExtra(bloopModule, sbtVersion, scalaVersion)
       |    List(bloopPlugin) ++ oldDependencies
       |  }
       |}
       |""".stripMargin
}
