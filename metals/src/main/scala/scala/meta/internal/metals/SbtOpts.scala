package scala.meta.internal.metals
// See NOTICE.md, this file contains parts that are derived from
// IntelliJ Scala, in particular SbtOpts.scala:
// https://github.com/JetBrains/intellij-scala/blob/e2c57778bb302a6f2f93e2628f6762ecbf76fb3a/scala/scala-impl/src/org/jetbrains/sbt/project/structure/SbtOpts.scala

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.meta.internal.io.FileIO
import scala.meta.io.AbsolutePath

/**
 * Support for the .sbtopts file loaded by the sbt launcher script as alternative to command line options.
 */
object SbtOpts {

  def fromWorkspace(workspace: AbsolutePath): List[String] = {
    val sbtOpts = workspace.resolve(".sbtopts")
    if (sbtOpts.isFile && Files.isReadable(sbtOpts.toNIO)) {
      val text = FileIO.slurp(sbtOpts, StandardCharsets.UTF_8)
      process(text.lines.map(_.trim).toList)
    } else {
      Nil
    }
  }

  def fromEnvironment: List[String] = {
    Option(System.getenv("SBT_OPTS")) match {
      case Some(value) => process(value.split(" ").toList)
      case None => Nil
    }
  }

  private val noShareOpts =
    "-Dsbt.global.base=project/.sbtboot -Dsbt.boot.directory=project/.boot -Dsbt.ivy.home=project/.ivy"
  private val noGlobalOpts = "-Dsbt.global.base=project/.sbtboot"
  private val debuggerOpts =
    "-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=n,address="

  private val sbtToJdkOpts: Map[String, String] = Map(
    "-sbt-boot" -> "-Dsbt.boot.directory=",
    "-sbt-dir" -> "-Dsbt.global.base=",
    "-ivy" -> "-Dsbt.ivy.home=",
    "-jvm-debug" -> debuggerOpts
  )

  private def process(opts: List[String]): List[String] = {
    opts.flatMap { opt =>
      if (opt.startsWith("-no-share"))
        Some(noShareOpts)
      else if (opt.startsWith("-no-global"))
        Some(noGlobalOpts)
      else if (sbtToJdkOpts.exists { case (k, _) => opt.startsWith(k) })
        processOptWithArg(opt)
      else if (opt.startsWith("-J"))
        Some(opt.substring(2))
      else if (opt.startsWith("-D"))
        Some(opt)
      else
        None
    }
  }

  private def processOptWithArg(opt: String): Option[String] = {
    sbtToJdkOpts.find { case (k, _) => opt.startsWith(k) }.flatMap {
      case (k, x) =>
        val v = opt.replace(k, "").trim
        if (v.isEmpty) None else Some(x + v)
    }
  }
}
