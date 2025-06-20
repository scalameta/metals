package scala.meta.internal.metals.debug.server

import java.io.File
import java.nio.file.Path

import scala.concurrent.ExecutionContext

import scala.meta.internal.metals.JavaBinary
import scala.meta.internal.metals.ManifestJar
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
      envVariables: List[String],
      logger: Logger,
      debug: Boolean = true,
  )(implicit ec: ExecutionContext): CancelableFuture[Unit] = {
    val fullClasspathStr =
      classPath.map(_.toString()).mkString(File.pathSeparator)
    val java = JavaBinary(userJavaHome).toString()
    val classpathOption = "-cp" :: fullClasspathStr :: Nil
    val cmd =
      java :: jvmOptions ::: (if (debug) enableDebugInterface :: Nil
                              else
                                Nil) ::: classpathOption ::: (className :: args)
    val cmdLength = cmd.foldLeft(0)(_ + _.length)
    val envOptions =
      envVariables.flatMap { line =>
        val eqIdx = line.indexOf("=")
        if (eqIdx > 0 && eqIdx != line.length - 1) {
          val key = line.substring(0, eqIdx)
          val value = line.substring(eqIdx + 1)
          Some(key -> value)
        } else None
      }.toMap

    // Note that we current only shorten the classpath portion and not other options
    // Thus we do not yet *guarantee* that the command will not exceed OS limits
    val process =
      if (cmdLength <= SystemProcess.processCmdCharLimit) {
        SystemProcess.run(
          cmd,
          root,
          redirectErrorOutput = false,
          envOptions,
          processErr = Some(logger.logError),
          processOut = Some(logger.logOutput),
        )
      } else {
        ManifestJar.withTempManifestJar(classPath) { manifestJar =>
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

  private def enableDebugInterface: String = {
    s"-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,quiet=n"
  }
}
