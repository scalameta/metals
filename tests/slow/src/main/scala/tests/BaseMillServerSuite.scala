package tests

import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.metals.JdkSources
import scala.meta.io.AbsolutePath

trait BaseMillServerSuite {
  this: BaseLspSuite =>

  private val millServerRegex =
    "(?m)^(\\d+) (mill[.]runner[.]MillServerMain|mill[.]daemon[.]MillDaemonMain)(.*)$".r

  def killMillServer(
      workspace: AbsolutePath
  ): Unit = if (!isWindows) {
    val home = JdkSources.defaultJavaHome(None).head
    for {
      processes <- ShellRunner.runSync(
        List(s"${home}/bin/jps", "-lm"),
        workspace,
        redirectErrorOutput = false,
      )
      process <- millServerRegex.findAllMatchIn(processes)
      // Mill 1.x starts a daemon per workspace, so only kill the one for this test
      if process.group(2).endsWith("MillServerMain") ||
        process.group(3).contains(workspace.toString())
    } {
      val pid = process.group(1)
      scribe.info(s"Killing mill server $pid")
      ShellRunner
        .runSync(
          List("kill", "-9", pid),
          workspace,
          redirectErrorOutput = false,
        )
    }
  } else {
    scribe.error("Can't kill Mill server on Windows.")
  }
}
