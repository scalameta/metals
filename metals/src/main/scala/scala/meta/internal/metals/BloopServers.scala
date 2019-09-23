package scala.meta.internal.metals

import java.io.InputStream
import java.io.PrintStream
import java.net.{InetAddress, InetSocketAddress}
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.scalasbt.ipcsocket.UnixDomainSocket
import org.scalasbt.ipcsocket.Win32NamedPipeSocket
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath
import scala.sys.process.Process
import scala.sys.process.ProcessLogger
import scala.util.Random
import scala.util.{Failure, Success}
import scala.util.control.NonFatal
import scala.util.Try

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
    icons: Icons,
    embedded: Embedded,
    statusBar: StatusBar
)(implicit ec: ExecutionContextExecutorService) {

  def newServer(
      maxRetries: Int = defaultRetries
  ): Future[Option[BuildServerConnection]] = {
    newServerUnsafe().map(Option(_)).recoverWith {
      case NonFatal(e) if maxRetries > 0 =>
        val retry = defaultRetries - maxRetries + 1
        val cause = e.getMessage
        scribe.warn(s"BSP retry $retry due to: $cause", e)
        newServer(maxRetries - 1)
    }
  }

  private def defaultRetries: Int =
    if (config.bloopProtocol.isTcp) {
      10
    } else {
      2
    }

  private def newServerUnsafe(): Future[BuildServerConnection] = {
    for {
      (protocol, cancelable) <- callBSP()
    } yield {
      BuildServerConnection.fromStreams(
        workspace,
        client,
        protocol.output,
        protocol.input,
        List(
          Cancelable(() => protocol.cancel()),
          cancelable
        ),
        "Bloop"
      )
    }
  }

  // Returns a random free port.
  private def randomPort(host: String): Int = {
    val s = new ServerSocket()
    s.setReuseAddress(true)
    s.bind(new InetSocketAddress(host, 0))
    val port = s.getLocalPort
    s.close()
    port
  }

  private def bspCommand: Array[String] = {
    if (config.isVerbose) Array("bsp", "--verbose")
    else Array("bsp")
  }

  private def callBSP(): Future[(BloopSocket, Cancelable)] = {
    if (config.bloopProtocol.isNamedPipe) callNamedPipeBsp()
    else if (config.bloopProtocol.isTcp) callTcpBsp()
    else callUnixBsp()
  }

  private def callNamedPipeBsp(): Future[(BloopSocket, Cancelable)] = {
    val pipeName = "\\\\.\\pipe\\metals" + Random.nextInt()
    val args = bspCommand ++ Array(
      "--protocol",
      "local",
      "--pipe-name",
      pipeName
    )
    callBloopMain(
      args,
      isOk = { () =>
        Try(BloopSocket.NamedPipe(new Win32NamedPipeSocket(pipeName)))
      }
    )
  }

  private def callTcpBsp(): Future[(BloopSocket, Cancelable)] = {
    val host = "127.0.0.1"
    val port = randomPort(host)
    val args = bspCommand ++ Array(
      "--protocol",
      "tcp",
      "--host",
      host,
      "--port",
      port.toString
    )
    callBloopMain(
      args,
      isOk = { () =>
        Try {
          val socket = new Socket()
          socket.setReuseAddress(true)
          socket.setTcpNoDelay(true)
          val address = InetAddress.getByName(host)
          socket.connect(new InetSocketAddress(address, port))
          BloopSocket.Tcp(socket)
        }
      }
    )
  }

  private def callUnixBsp(): Future[(BloopSocket, Cancelable)] = {
    val socket = BloopServers.newSocketFile()
    val args = bspCommand ++ Array(
      "--protocol",
      "local",
      "--socket",
      socket.toString
    )
    callBloopMain(
      args,
      isOk = { () =>
        Try(
          BloopSocket.Unix(new UnixDomainSocket(socket.toFile.getCanonicalPath))
        )
      }
    )
  }

  private def callBloopMain(
      args: Array[String],
      isOk: () => Try[BloopSocket]
  ): Future[(BloopSocket, Cancelable)] = {
    val cancelable = callBloopMain(args)
    waitUntilSuccess(isOk).map {
      case Success(socket) =>
        (socket, cancelable)
      case Failure(_) =>
        cancelable.cancel()
        throw NoResponse
    }
  }

  private def callBloopMain(args: Array[String]): Cancelable = {
    val logger = MetalsLogger.newBspLogger(workspace)
    if (bloopCommandLineIsInstalled(8212)) {
      scribe.info(s"running installed 'bloop ${args.mkString(" ")}'")
      val bspProcess = Process(
        Array("python", embedded.bloopPy.toString()) ++ args,
        cwd = workspace.toFile
      ).run(
        ProcessLogger(
          out => logger.info(out),
          err => logger.error(err)
        )
      )
      Cancelable(() => bspProcess.destroy())
    } else {
      scribe.info(s"running embedded 'bloop ${args.mkString(" ")}'")
      embedded.bloopJars match {
        case Some(classloaders) =>
          val cancelMain = Promise[java.lang.Boolean]()
          val job = ec.submit(new Runnable {
            override def run(): Unit = {
              callBloopReflectMain(
                classloaders,
                args,
                cancelMain.future.asJava
              )
            }
          })
          new MutableCancelable()
            .add(Cancelable(() => job.cancel(false)))
            .add(Cancelable(() => cancelMain.trySuccess(true)))
        case None =>
          scribe.error(
            "not found: bloop classloader. To fix this problem, try installing Bloop from https://scalacenter.github.io/bloop/setup"
          )
          Cancelable.empty
      }
    }
  }

  /**
   * Uses runtime reflection to invoke the `reflectiveMain` function in bloop.
   */
  private def callBloopReflectMain(
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
    // both use err. out is reserved to metals.
    val outStream = System.err
    val errStream = System.err
    val exitCode = reflectiveMain.invoke(
      null, // static method has no caller object.
      args,
      workspace.toNIO,
      new InputStream { override def read(): Int = -1 },
      outStream,
      errStream,
      new Properties(),
      cancelMain
    )
    scribe.info(s"bloop exit: $exitCode")
  }

  private def bloopCommandLineIsInstalled(port: Int): Boolean = {
    var socket: Socket = null
    try {
      socket = new Socket()
      socket.setReuseAddress(true)
      socket.setTcpNoDelay(true)
      socket.connect(
        new InetSocketAddress(InetAddress.getLoopbackAddress, port)
      )
      socket.isConnected
    } catch {
      case NonFatal(_) => false
    } finally {
      if (socket != null)
        try {
          socket.close
        } catch {
          case NonFatal(_) =>
        }
    }
  }

  case object NoResponse extends Exception("no response: bloop bsp")

  private def waitUntilSuccess(
      isOk: () => Try[BloopSocket]
  ): Future[Try[BloopSocket]] = {
    val retryDelayMillis: Long = 200
    val maxRetries: Int = 40
    val promise = Promise[Try[BloopSocket]]()
    var remainingRetries = maxRetries
    val tick = sh.scheduleAtFixedRate(
      new Runnable {
        override def run(): Unit = {
          isOk() match {
            case s @ Success(_) =>
              promise.complete(Success(s))
            case f @ Failure(ex) if (remainingRetries < 0) =>
              promise.complete(Success(f))
            case _ =>
              remainingRetries -= 1
          }
        }
      },
      0,
      retryDelayMillis,
      TimeUnit.MILLISECONDS
    )
    promise.future.onComplete(_ => tick.cancel(false))
    promise.future.recover {
      case NonFatal(e) =>
        scribe.error("Unexpected error using backup", e)
        Failure(e)
    }
  }
}

object BloopServers {
  private def newSocketFile(): AbsolutePath = {
    val tmp = Files.createTempDirectory("bsp")
    val id = java.lang.Long.toString(Random.nextLong(), Character.MAX_RADIX)
    val socket = tmp.resolve(s"$id.socket")
    socket.toFile.deleteOnExit()
    AbsolutePath(socket)
  }
}
