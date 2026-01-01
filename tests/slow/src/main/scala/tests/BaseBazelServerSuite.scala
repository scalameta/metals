package tests

import scala.meta.internal.builds.ShellRunner

trait BaseBazelServerSuite {
  this: BaseLspSuite =>

  def cleanBazelServer(): Unit = {
    try {
      // Shutdown Bazel server after each test
      ShellRunner.runSync(
        List("bazel", "shutdown"),
        workspace,
        redirectErrorOutput = false,
      ) match {
        case Some(output) =>
          scribe.info(s"Bazel shutdown completed: $output")
        case None =>
          scribe.warn("Bazel shutdown command failed or produced no output")
      }
    } catch {
      case e: Exception =>
        scribe.warn(s"Failed to shutdown Bazel server: ${e.getMessage}")
    } finally {
      cleanWorkspace()
    }
  }

}
