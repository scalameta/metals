package scala.meta.internal.eval

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files.createTempDirectory
import java.nio.file.Files.isDirectory
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import com.zaxxer.nuprocess.NuAbstractProcessHandler
import com.zaxxer.nuprocess.NuProcess
import com.zaxxer.nuprocess.NuProcessBuilder
import org.eclipse.lsp4j.debug.OutputEventArgumentsCategory._
import org.eclipse.lsp4j.debug.TerminatedEventArguments
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.meta.internal.eval.JvmDebugProtocol.LaunchParameters
import scala.meta.internal.eval.JvmDebugProtocol.outputEvent

class DebugSession(
    val parameters: LaunchParameters,
    process: NuProcess,
    listener: DebuggeeListener
) {
  def exitCode: Future[Long] = listener.exitCode

  def shutdown()(implicit executionContext: ExecutionContext): Future[Unit] = {
    def kill(force: Boolean): Boolean = {
      process.destroy(force)
      process.waitFor(5, TimeUnit.SECONDS) != Integer.MIN_VALUE
    }

    listener.close()
    for {
      killedGracefully <- Future(kill(force = false))
      killed <- Future(killedGracefully || kill(force = true))
      _ <- Future {
        if (!killed) {
          val message = "Could not kill process: " + process.getPID
          throw new IllegalStateException(message)
        }
      }
    } yield ()
  }
}

class DebuggeeListener(client: IDebugProtocolClient)
    extends NuAbstractProcessHandler
    with AutoCloseable {
  private var closed = false
  private val status = Promise[Long]()

  def exitCode: Future[Long] = status.future

  override def onStdout(buffer: ByteBuffer, closed: Boolean): Unit = {
    if (!closed) {
      val message = StandardCharsets.UTF_8.decode(buffer).toString
      client.output(outputEvent(STDOUT, message))
    }
  }

  override def onStderr(buffer: ByteBuffer, closed: Boolean): Unit = {
    if (!closed) {
      val message = StandardCharsets.UTF_8.decode(buffer).toString
      client.output(outputEvent(STDERR, message))
    }
  }

  override def onExit(statusCode: Int): Unit = {
    status.success(statusCode)
  }

  override def close(): Unit = {
    closed = true
  }
}

final class DebugSessionFactory(client: IDebugProtocolClient) {
  def create(
      params: LaunchParameters
  )(implicit ec: ExecutionContext): DebugSession = {
    val command = createCommand(params)
    val workingDir = createWorkingDir(params)
    val listener = new DebuggeeListener(client)

    val builder = new NuProcessBuilder(listener, command: _*)
    builder.setCwd(workingDir)
    val process = builder.start()

    new DebugSession(params, process, listener)
  }

  private def createCommand(params: LaunchParameters): Array[String] = {
    val java = Paths
      .get(System.getProperty("java.home"))
      .resolve("bin")
      .resolve("java")
      .toString

    val classpath = params.classpath.mkString(File.pathSeparator)
    Array(java, "-cp", classpath, params.mainClass)
  }

  private def createWorkingDir(params: LaunchParameters): Path = {
    Option(params.cwd).map(Paths.get(_)) match {
      case Some(path) if isDirectory(path) =>
        path
      case _ =>
        // prevent using the working dir of the current process
        val workingDir = createTempDirectory("metals-debug-session-")
        workingDir.toFile.deleteOnExit()
        workingDir
    }
  }
}
