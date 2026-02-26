package tests

import scala.meta.internal.builds.ShellRunner

trait BaseBazelNativeServerSuite {
  this: BaseLspSuite =>

  def cleanBazelServer(): Unit = {
    try {
      ShellRunner.runSync(
        List("bazel", "shutdown"),
        workspace,
        redirectErrorOutput = false,
      ) match {
        case Some(output) =>
          scribe.info(s"[BazelNative] Bazel shutdown completed: $output")
        case None =>
          scribe.warn(
            "[BazelNative] Bazel shutdown command failed or produced no output"
          )
      }
    } catch {
      case e: Exception =>
        scribe.warn(
          s"[BazelNative] Failed to shutdown Bazel server: ${e.getMessage}"
        )
    } finally {
      cleanWorkspace()
    }
  }
}
