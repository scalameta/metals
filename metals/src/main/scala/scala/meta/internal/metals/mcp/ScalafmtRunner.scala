package scala.meta.internal.metals.mcp

import java.util.regex.Pattern

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration._

import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.scalacli.ScalaCli
import scala.meta.io.AbsolutePath

final class ScalafmtRunner(
    workspace: AbsolutePath,
    userConfig: () => UserConfiguration,
)(implicit ec: ExecutionContext) {
  def formatAll(): Future[Either[String, Unit]] = {
    // Scalafmt sees symlink-resolved paths, e.g. /private/var on macOS.
    val rootPattern = Pattern.quote(workspace.toNIO.toRealPath().toString)
    format(
      List(
        "--scalafmt-arg=--exclude",
        s"--scalafmt-arg=^$rootPattern[/\\\\](.*[/\\\\])?target[/\\\\]",
        ".",
      )
    )
  }

  def format(arguments: List[String]): Future[Either[String, Unit]] =
    Future {
      val scalaCli =
        ScalaCli.localScalaCli(userConfig()).getOrElse(ScalaCli.jvmBased())
      val command = scalaCli.command.toList ++ ("fmt" :: arguments)
      scribe.info(s"Formatting with command: $command")
      val errorReporting = new StringBuilder()
      val result = ShellRunner.runSync(
        command,
        workspace,
        redirectErrorOutput = false,
        processErr = { err =>
          scribe.error(err)
          errorReporting.append(err + "\n")
        },
        timeout = 2.minutes,
      )
      result match {
        case Some(_) => Right(())
        case None =>
          val filtered = errorReporting
            .toString()
            .linesIterator
            .filterNot(ScalafmtRunner.isNoiseLine)
            .mkString("\n")
            .trim
          Left(
            if (filtered.isEmpty) "Scalafmt failed with no output."
            else filtered
          )
      }
    }
}

object ScalafmtRunner {
  private val moreStackFrames = raw"""\.\.\. \d+ more""".r

  private def isNoiseLine(line: String): Boolean = {
    val trimmed = line.trim
    trimmed.startsWith("at ") ||
    moreStackFrames.matches(trimmed) ||
    trimmed.startsWith("WARNING:") ||
    trimmed.startsWith(
      "Warning: Only java properties are supported in JAVA_OPTS"
    )
  }
}
