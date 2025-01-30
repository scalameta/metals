package tests

import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.metals.JdkSources
import scala.meta.io.AbsolutePath

trait BaseMillServerSuite {
  this: BaseLspSuite =>

  def killMillServer(
      workspace: AbsolutePath
  ): Unit = if (!isWindows) {
    val home = JdkSources.defaultJavaHome(None).head
    ShellRunner
      .runSync(
        List(s"${home}/bin/jps", "-l"),
        workspace,
        redirectErrorOutput = false,
      )
      .flatMap { processes =>
        "(\\d+) mill[.]runner[.]MillServerMain".r
          .findFirstMatchIn(processes)
          .map(_.group(1).toInt)
      }
      .foreach { case pid =>
        scribe.info(s"Killing mill server $pid")
        ShellRunner
          .runSync(
            List("kill", "-9", pid.toString()),
            workspace,
            redirectErrorOutput = false,
          )
      }
  } else {
    scribe.error("Can't kill Mill server on Windows.")
  }
}
