package scala.meta.internal.metals

import java.io.InputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.eclipse.lsp4j.jsonrpc.Launcher
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
    icons: Icons,
    embedded: Embedded,
    statusBar: StatusBar
)(implicit ec: ExecutionContextExecutorService) {

  def newServer(
      maxRetries: Int = defaultRetries
  ): Future[BuildServerConnection] = {
    newServerUnsafe().recoverWith {
      case NonFatal(_) if maxRetries > 0 =>
        scribe.warn(s"BSP retry ${defaultRetries - maxRetries + 1}")
        newServer(maxRetries - 1)
    }
  }

  private def defaultRetries: Int =
    if (config.bloopProtocol.isTcp) {
      // NOTE(olafur) The TCP socket establishment is quite fragile on
      // on Windows and retries seem to help. Maybe named pipes will help
      // improve stability, or somebody who is more versed with I/O on Windows
      // and can fix my crappy socket code.
      10
    } else {
      0
    }

  private def newServerUnsafe(): Future[BuildServerConnection] = {
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
      val initializeResult =
        BuildServerConnection.initialize(workspace, remoteServer)
      BuildServerConnection(
        workspace,
        client,
        remoteServer,
        cancelables,
        initializeResult
      )
    }
  }

  // Returns a random free port.
  private def randomPort(host: String): Int = {
    val s = new ServerSocket()
    s.bind(new InetSocketAddress(host, 0))
    val port = s.getLocalPort
    s.close()
    port
  }

  private def callBSP(): Future[(BloopSocket, Cancelable)] = {
    if (config.bloopProtocol.isNamedPipe) callNamedPipeBsp()
    if (config.bloopProtocol.isTcp) callTcpBsp()
    else callUnixBsp()
  }

  private def callNamedPipeBsp(): Future[(BloopSocket, Cancelable)] = {
    val pipeName = "\\\\.\\pipe\\metals" + Random.nextInt()
    val args = Array(
      "bsp",
      "--protocol",
      "local",
      "--pipe-name",
      pipeName
    )
    callBloopMain(
      args,
      isOk = { () =>
        Thread.sleep(1000)
        true
      },
      onSuccess = { () =>
        val connection = new Win32NamedPipeSocket(pipeName)
        BloopSocket.NamedPipe(connection)
      }
    )
  }

  private def callTcpBsp(): Future[(BloopSocket, Cancelable)] = {
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
    callBloopMain(
      args,
      isOk = { () =>
        // On macos/linux we can retry `new Socket(host, post)` until it stops throwing an exception
        // but on windows this approach will spoil the socket somehow resulting in timeouts for
        // the `build/initialize` handshake. Until Bloop `--protocol local` implements support
        // for communicating a safe moment to establish the client socket we Thread.sleep blindly.
        Thread.sleep(3000)
        true
      },
      onSuccess = { () =>
        val socket = new Socket(host, port)
        BloopSocket.Tcp(socket)
      }
    )
  }

  private def callUnixBsp(): Future[(BloopSocket, Cancelable)] = {
    val socket = BloopServers.newSocketFile()
    val args = Array(
      "bsp",
      "--protocol",
      "local",
      "--socket",
      socket.toString
    )
    callBloopMain(
      args,
      isOk = { () =>
        Files.exists(socket.toNIO)
      },
      onSuccess = { () =>
        BloopSocket.Unix(new UnixDomainSocket(socket.toFile.getCanonicalPath))
      }
    )
  }

  private def callBloopMain(
      args: Array[String],
      isOk: () => Boolean,
      onSuccess: () => BloopSocket
  ): Future[(BloopSocket, Cancelable)] = {
    val cancelable = callBloopMain(args)
    waitUntilSuccess(isOk).map { confirmation =>
      if (confirmation.isYes) {
        (onSuccess(), cancelable)
      } else {
        cancelable.cancel()
        throw NoResponse
      }
    }
  }

  private def callBloopMain(args: Array[String]): Cancelable = {
    scribe.info(s"running 'bloop ${args.mkString(" ")}'")
    val logger = MetalsLogger.newBspLogger(workspace)
    if (bloopCommandLineIsInstalled(workspace)) {
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
      val output = Process(
        List("python", embedded.bloopPy.toString(), "help"),
        cwd = workspace.toFile
      ).!!(ProcessLogger(_ => ()))
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
    promise.future.recover {
      case NonFatal(e) =>
        scribe.error("Unexpected error using backup", e)
        Confirmation.No
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
