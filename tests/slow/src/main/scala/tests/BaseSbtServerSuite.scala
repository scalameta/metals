package tests

import scala.concurrent.duration.DurationInt
import scala.util.control.NonFatal

import scala.meta.internal.builds.ShellRunner

/**
 * sbt servers started for BSP outlive the connection (sbt 2.x ones never exit),
 * so they need to be killed after each test to avoid running out of memory.
 */
trait BaseSbtServerSuite extends BaseLspSuite {

  override def afterEach(context: AfterEach): Unit = {
    super.afterEach(context)
    cancelServer()
    killSbtServers()
  }

  private def killSbtServers(): Unit = if (!isWindows) {
    try {
      scribe.info(s"Killing sbt server running in $workspace")
      for {
        _ <- ShellRunner.runSync(
          List("sbt", "shutdown"),
          workspace,
          redirectErrorOutput = false,
          timeout = 30.seconds,
        )
      } {
        scribe.info(s"Killed sbt server running in $workspace")
      }
    } catch {
      case NonFatal(e) =>
        scribe.error(s"Failed to kill sbt server running in $workspace", e)
    }
  }
}
