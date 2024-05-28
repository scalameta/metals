package scala.meta.internal.metals.debug.server

import java.io.BufferedOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarFile
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

import scala.concurrent.ExecutionContext
import scala.util.Properties

import scala.meta.internal.metals.JavaBinary
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.process.SystemProcess
import scala.meta.io.AbsolutePath

import ch.epfl.scala.debugadapter.CancelableFuture

object Run {

  def runMain(
      root: AbsolutePath,
      classPath: Seq[Path],
      userJavaHome: Option[String],
      className: String,
      args: List[String],
      jvmOptions: List[String],
      evnVariables: List[String],
      logger: Logger,
  )(implicit ec: ExecutionContext): CancelableFuture[Unit] = {
    val fullClasspathStr =
      classPath.map(_.toString()).mkString(File.pathSeparator)
    val java = JavaBinary(userJavaHome).toString()
    val classpathOption = "-cp" :: fullClasspathStr :: Nil
    val cmd =
      java :: (jvmOptions :+ enableDebugInterface) ::: classpathOption ::: (className :: args)
    val cmdLength = cmd.foldLeft(0)(_ + _.length)
    val envOptions =
      evnVariables.flatMap { line =>
        val eqIdx = line.indexOf("=")
        if (eqIdx > 0 && eqIdx != line.length - 1) {
          val key = line.substring(0, eqIdx)
          val value = line.substring(eqIdx + 1)
          Some(key -> value)
        } else None
      }.toMap

    // Windows max cmd line length is 32767, which seems to be the least of the common shells.
    val processCmdCharLimit = 30000

    // Note that we current only shorten the classpath portion and not other options
    // Thus we do not yet *guarantee* that the command will not exceed OS limits
    val process =
      if (cmdLength <= processCmdCharLimit) {
        SystemProcess.run(
          cmd,
          root,
          redirectErrorOutput = false,
          envOptions,
          processErr = Some(logger.logError),
          processOut = Some(logger.logOutput),
        )
      } else {
        withTempManifestJar(classPath) { manifestJar =>
          val shortClasspathOption = "-cp" :: manifestJar.syntax :: Nil
          val shortCmd =
            java :: jvmOptions ::: shortClasspathOption ::: (className :: args) // appOptions
          SystemProcess.run(
            shortCmd,
            root,
            redirectErrorOutput = false,
            envOptions,
            processErr = Some(logger.logError),
            processOut = Some(logger.logOutput),
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

  private def withTempManifestJar(
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
    val syntax = path.toUri.toURL.getPath
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

  private def enableDebugInterface: String = {
    s"-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,quiet=n"
  }
}
