package scala.meta.internal.metals.debug.server

import java.io.BufferedOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.jar.Attributes
import java.util.jar.JarFile
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

import scala.concurrent.ExecutionContext
import scala.util.Properties

import scala.meta.internal.metals.JavaBinary
import scala.meta.internal.metals.JdkSources
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

    // Windows max cmd line length is 32767, which seems to be the least of the common shells.
    val processCmdCharLimit = 30000

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
      if (cmdLength <= processCmdCharLimit) {
        SystemProcess.run(
          cmd,
          root,
          redirectErrorOutput = false,
          envOptions,
          processErr = Some(logError),
          processOut = Some(logOutput),
        )
      } else {
        Utils.withTempManifestJar(classPath) { manifestJar =>
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

object Utils {
  def withTempManifestJar(
      classpath: Seq[Path]
  )(
      op: AbsolutePath => SystemProcess
  )(implicit ec: ExecutionContext): SystemProcess = {

    val manifestJar =
      Files.createTempFile("jvm-forker-manifest", ".jar").toAbsolutePath
    val manifestJarAbs = AbsolutePath(manifestJar)

    // Add trailing slash to directories so that manifest dir entries work
    val classpathStr =
      classpath.map(addTrailingSlashToDirectories).mkString(" ")

    val manifest = new Manifest()
    manifest.getMainAttributes.put(Attributes.Name.MANIFEST_VERSION, "1.0")
    manifest.getMainAttributes.put(Attributes.Name.CLASS_PATH, classpathStr)

    val zipOut = new ZipOutputStream(Files.newOutputStream(manifestJar))
    try {
      val zipEntry = new ZipEntry(JarFile.MANIFEST_NAME)
      zipOut.putNextEntry(zipEntry)
      manifest.write(new BufferedOutputStream(zipOut))
      zipOut.closeEntry()
    } finally {
      zipOut.close()
    }

    val process = op(manifestJarAbs)
    process.complete.onComplete { case _ =>
      manifestJarAbs.delete()
    }
    process
  }

  private def addTrailingSlashToDirectories(path: Path): String = {
    // NOTE(olafur): manifest jars must use URL-encoded paths.
    // https://docs.oracle.com/javase/7/docs/technotes/guides/jar/jar.html
    val syntax = path.toURI.toURL.getPath
    val separatorAdded = {
      if (syntax.endsWith(".jar") || syntax.endsWith(File.separator)) {
        syntax
      } else {
        syntax + File.separator
      }
    }

    if (Properties.isWin) {
      // Prepend drive letters in windows with slash
      if (separatorAdded.indexOf(":") != 1) separatorAdded
      else File.separator + separatorAdded
    } else {
      separatorAdded
    }
  }
}
