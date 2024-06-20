package scala.meta.internal.metals.debug.server

import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

import scala.concurrent.ExecutionContext

import scala.meta.internal.metals.JavaBinary
import scala.meta.internal.metals.JdkSources
import scala.meta.internal.metals.ManifestJar
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.process.SystemProcess
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.ScalaMainClass
import ch.epfl.scala.debugadapter.CancelableFuture
import ch.epfl.scala.debugadapter.DebuggeeListener
import ch.epfl.scala.debugadapter.JavaRuntime
import ch.epfl.scala.debugadapter.Library
import ch.epfl.scala.debugadapter.Module
import ch.epfl.scala.debugadapter.UnmanagedEntry

class MainClassDebugAdapter(
    root: AbsolutePath,
    mainClass: ScalaMainClass,
    project: DebugeeProject,
    userJavaHome: Option[String],
)(implicit ec: ExecutionContext)
    extends MetalsDebuggee() {

  private val initialized = new AtomicBoolean(false)

  override def modules: Seq[Module] = project.modules

  override def libraries: Seq[Library] = project.libraries

  override def unmanagedEntries: Seq[UnmanagedEntry] = project.unmanagedEntries

  private final val JDINotificationPrefix =
    "Listening for transport dt_socket at address: "

  protected def scalaVersionOpt: Option[String] = project.scalaVersion

  val javaRuntime: Option[JavaRuntime] =
    JdkSources
      .defaultJavaHome(userJavaHome)
      .flatMap(path => JavaRuntime(path.toNIO))
      .headOption

  def name: String =
    s"${getClass.getSimpleName}(${project.name}, ${mainClass.getClassName()})"
  def run(listener: DebuggeeListener): CancelableFuture[Unit] = {
    val jvmOptions =
      mainClass.getJvmOptions.asScala.toList :+ enableDebugInterface
    val fullClasspathStr =
      classPath.map(_.toString()).mkString(File.pathSeparator)
    val java = JavaBinary(userJavaHome).toString()
    val classpathOption = "-cp" :: fullClasspathStr :: Nil
    val appOptions =
      mainClass.getClassName :: mainClass.getArguments().asScala.toList
    val cmd = java :: jvmOptions ::: classpathOption ::: appOptions
    val cmdLength = cmd.foldLeft(0)(_ + _.length)
    val envOptions =
      mainClass
        .getEnvironmentVariables()
        .asScala
        .flatMap { line =>
          val eqIdx = line.indexOf("=")
          if (eqIdx > 0 && eqIdx != line.length - 1) {
            val key = line.substring(0, eqIdx)
            val value = line.substring(eqIdx + 1)
            Some(key -> value)
          } else None
        }
        .toMap

    def logError(errorMessage: String) = {
      listener.err(errorMessage)
      scribe.error(errorMessage)
    }

    def logOutput(msg: String) = {
      if (msg.startsWith(JDINotificationPrefix)) {
        if (initialized.compareAndSet(false, true)) {
          val port = Integer.parseInt(msg.drop(JDINotificationPrefix.length))
          val address = new InetSocketAddress("127.0.0.1", port)
          listener.onListening(address)
        }
      } else {
        listener.out(msg)
      }
    }

    // Note that we current only shorten the classpath portion and not other options
    // Thus we do not yet *guarantee* that the command will not exceed OS limits
    val process =
      if (cmdLength <= SystemProcess.processCmdCharLimit) {
        SystemProcess.run(
          cmd,
          root,
          redirectErrorOutput = false,
          envOptions,
          processErr = Some(logError),
          processOut = Some(logOutput),
        )
      } else {
        ManifestJar.withTempManifestJar(classPath) { manifestJar =>
          val shortClasspathOption = "-cp" :: manifestJar.syntax :: Nil
          val shortCmd =
            java :: jvmOptions ::: shortClasspathOption ::: appOptions
          SystemProcess.run(
            shortCmd,
            root,
            redirectErrorOutput = false,
            envOptions,
            processErr = Some(logError),
            processOut = Some(logOutput),
          )
        }
      }

    new CancelableFuture[Unit] {
      def future = process.complete.map { code =>
        if (code != 0)
          throw new Exception(s"debuggee failed with error code $code")
      }
      def cancel(): Unit = process.cancel
    }
  }

  private def enableDebugInterface: String = {
    s"-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,quiet=n"
  }
}
