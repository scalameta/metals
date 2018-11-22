package scala.meta.internal.metals

import com.geirsson.coursiersmall
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.scalasbt.ipcsocket.UnixDomainSocket
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.Duration
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath
import scala.sys.process.Process
import scala.sys.process.ProcessLogger
import scala.util.Random
import scala.util.Success
import scala.util.control.NonFatal

/**
 * Establishes a connection with a bloop server.
 *
 * Connects to a running bloop server instance if it is installed on the user
 * machine and has compatible version (+v1.1.0). Ignores the installed bloop
 * server instance if it is v1.0.0.
 *
 * Otherwise, if the user doesn't have bloop installed on the machine, uses
 * Coursier to fetch the jars for ch.epfl.scala:bloop-frontend and start a new
 * bloop server using classloading. A bloop server that starts via classloading
 * stops when when the metals server stops. Only metals can access the classloaded server,
 * the user cannot call it from a command-line interface.
 *
 * Eventually, this class may be superseded by "BSP connection protocol":
 * https://github.com/scalacenter/bsp/blob/master/docs/bsp.md#bsp-connection-protocol
 */
final class BloopServers(
    sh: ScheduledExecutorService,
    workspace: AbsolutePath,
    client: MetalsBuildClient,
    config: MetalsServerConfig,
    icons: Icons
)(implicit ec: ExecutionContextExecutorService, statusBar: StatusBar) {

  def newServer(): Future[BuildServerConnection] = {
    for {
      (protocol, cancelable) <- callBSP()
    } yield {
      val tracePrinter = GlobalTrace.setupTracePrinter("BSP")
      val launcher = new Launcher.Builder[MetalsBuildServer]()
        .traceMessages(tracePrinter)
        .setRemoteInterface(classOf[MetalsBuildServer])
        .setExecutorService(ec)
        .setInput(protocol.input)
        .setOutput(protocol.output)
        .setLocalService(client)
        .create()
      val listening = launcher.startListening()
      val remoteServer = launcher.getRemoteProxy
      client.onConnect(remoteServer)
      val cancelables = List(
        Cancelable(() => protocol.cancel()),
        cancelable,
        Cancelable(() => listening.cancel(true))
      )
      BuildServerConnection(workspace, client, remoteServer, cancelables)
    }
  }

  private def randomPort(host: String): Int = {
    val s = new ServerSocket()
    s.bind(new InetSocketAddress(host, 0))
    val port = s.getLocalPort
    s.close()
    port
  }

  sealed abstract class Protocol extends Cancelable {
    import Protocol._
    def input: InputStream = this match {
      case Unix(socket) => socket.getInputStream
      case Tcp(socket) => socket.getInputStream
    }
    def output: OutputStream = this match {
      case Unix(socket) => socket.getOutputStream
      case Tcp(socket) =>
        new OutputStream {
          override def write(b: Int): Unit = {
            socket.getOutputStream.write(b)
            socket.getOutputStream.flush()
          }
        }
    }
    override def cancel(): Unit = this match {
      case Unix(socket) =>
        if (!socket.isInputShutdown) socket.shutdownInput()
        if (!socket.isOutputShutdown) socket.shutdownOutput()
        socket.close()
      case Tcp(socket) =>
        if (socket.isClosed) {
          socket.close()
        }
    }
  }
  object Protocol {
    case class Unix(socket: UnixDomainSocket) extends Protocol
    case class Tcp(socket: Socket) extends Protocol
  }

  private def callBSP(): Future[(Protocol, Cancelable)] = {
    if (config.bloopProtocol.isTcp) callTcpBsp()
    else callUnixBsp()
  }

  private def callTcpBsp(): Future[(Protocol, Cancelable)] = {
    val host = "127.0.0.1"
    val port = randomPort(host)
    val args = Array(
      "bsp",
      "--protocol",
      "tcp",
      "--host",
      host,
      "--port",
      port.toString
    )
    scribe.info(s"running '${args.mkString(" ")}'")
    val cancelable = callBloopMain(args)
    var connection: Socket = null
    for {
      confirmation <- waitUntilSuccess(() => {
        try {
          connection = new Socket(host, port)
          true
        } catch {
          case NonFatal(_) =>
            false
        }
      })
    } yield {
      if (confirmation.isYes) {
        (Protocol.Tcp(connection), cancelable)
      } else {
        cancelable.cancel()
        throw NoResponse
      }
    }
  }

  private def callUnixBsp(): Future[(Protocol, Cancelable)] = {
    val socket = BloopServers.newSocketFile()
    val args = Array(
      "bsp",
      "--protocol",
      "local",
      "--socket",
      socket.toString
    )
    val cancelable = callBloopMain(args)
    for {
      confirmation <- waitUntilSuccess(() => Files.exists(socket.toNIO))
    } yield {
      if (confirmation.isYes) {
        val connection = new UnixDomainSocket(socket.toFile.getCanonicalPath)
        (Protocol.Unix(connection), cancelable)
      } else {
        cancelable.cancel()
        throw NoResponse
      }
    }
  }

  private def callBloopMain(args: Array[String]): Cancelable = {
    val logger = MetalsLogger.newBspLogger(workspace)
    if (bloopCommandLineIsInstalled(workspace)) {
      val bspProcess = Process(
        "bloop" +: args,
        cwd = workspace.toFile
      ).run(
        ProcessLogger(
          out => logger.info(out),
          err => logger.error(err)
        )
      )
      Cancelable(() => bspProcess.destroy())
    } else {
      bloopJars match {
        case Some(classloaders) =>
          val cancelMain = Promise[java.lang.Boolean]()
          val job = ec.submit(new Runnable {
            override def run(): Unit = {
              callBloopReflectiveMain(
                classloaders,
                args,
                cancelMain.future.asJava
              )
            }
          })
          new MutableCancelable()
            .add(Cancelable(() => job.cancel(true)))
            .add(Cancelable(() => cancelMain.trySuccess(true)))
        case None =>
          Cancelable.empty
      }
    }
  }

  /**
   * Uses runtime reflection to invoke the `reflectiveMain` function in bloop.
   */
  private def callBloopReflectiveMain(
      classLoader: ClassLoader,
      args: Array[String],
      cancelMain: CompletableFuture[java.lang.Boolean]
  ): Unit = {
    val cls = classLoader.loadClass("bloop.Cli")
    val reflectiveMain = cls.getMethod(
      "reflectMain",
      classOf[Array[String]],
      classOf[Path],
      classOf[InputStream],
      classOf[PrintStream],
      classOf[PrintStream],
      classOf[Properties],
      classOf[CompletableFuture[java.lang.Boolean]]
    )
    val ps = System.out
    val exitCode = reflectiveMain.invoke(
      null, // static method has no caller object.
      args,
      workspace.toNIO,
      new InputStream { override def read(): Int = -1 },
      ps,
      ps,
      new Properties(),
      cancelMain
    )
    scribe.info(s"bloop exit: $exitCode")
  }

  private def bloopCommandLineIsInstalled(workspace: AbsolutePath): Boolean = {
    try {
      val output = Process(List("bloop", "help"), cwd = workspace.toFile)
        .!!(ProcessLogger(_ => ()))
      // NOTE: our BSP integration requires bloop 1.1 or higher so we ensure
      // users are on an older version.
      val isOldVersion =
        output.startsWith("bloop 1.0.0\n") ||
          output.startsWith("bloop 0")
      !isOldVersion
    } catch {
      case NonFatal(_) =>
        false
    }
  }

  case object NoResponse extends Exception("no response: bloop bsp")

  private def waitUntilSuccess(isOk: () => Boolean): Future[Confirmation] = {
    val retryDelayMillis: Long = 200
    val maxRetries: Int = 40
    val promise = Promise[Confirmation]()
    var remainingRetries = maxRetries
    val tick = sh.scheduleAtFixedRate(
      new Runnable {
        override def run(): Unit = {
          if (isOk()) {
            promise.complete(Success(Confirmation.Yes))
          } else if (remainingRetries < 0) {
            promise.complete(Success(Confirmation.No))
          } else {
            remainingRetries -= 1
          }
        }
      },
      0,
      retryDelayMillis,
      TimeUnit.MILLISECONDS
    )
    promise.future.onComplete(_ => tick.cancel(true))
    promise.future
  }

  lazy val bloopJars: Option[ClassLoader] = {
    val promise = Promise[Unit]()
    promise.future.trackInStatusBar(s"${icons.sync}Downloading Bloop")
    try {
      Some(BloopServers.newBloopClassloader())
    } catch {
      case NonFatal(e) =>
        scribe.error("Failed to classload bloop, compilation will not work", e)
        None
    } finally {
      promise.trySuccess(())
    }
  }

}

object BloopServers {
  private def newBloopClassloader(): ClassLoader = {
    val settings = new coursiersmall.Settings()
      .withTtl(Some(Duration.Inf))
      .withDependencies(
        List(
          new coursiersmall.Dependency(
            "ch.epfl.scala",
            "bloop-frontend_2.12",
            BuildInfo.bloopVersion
          )
        )
      )
      .withRepositories(
        new coursiersmall.Settings().repositories ++ List(
          coursiersmall.Repository.SonatypeReleases,
          new coursiersmall.Repository.Maven(
            "https://dl.bintray.com/scalacenter/releases"
          )
        )
      )
    val jars = coursiersmall.CoursierSmall.fetch(settings)
    val classloader =
      new URLClassLoader(jars.iterator.map(_.toUri.toURL).toArray, null)
    classloader
  }
  private def newSocketFile(): AbsolutePath = {
    val tmp = Files.createTempDirectory("bsp")
    val id = java.lang.Long.toString(Random.nextLong(), Character.MAX_RADIX)
    val socket = tmp.resolve(s"$id.socket")
    socket.toFile.deleteOnExit()
    AbsolutePath(socket)
  }
}
