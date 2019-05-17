package tests

import scala.concurrent.Future
import scala.meta.internal.metals.ServerCommands

/**
 * Debugger is a separate protocol, hence its tests are inside [[tests.debug]] package.
 * Here we only test if metal can simply start a debug session
 */
object DebuggerLaunchSuite extends BaseSlowSuite("debug") {
  testAsync("attach") {
    for {
      _ <- server.initialize(
        """|
           |/metals.json
           |{"a": {}}
           |""".stripMargin
      )
      _ <- startDebugSession()
    } yield ()
  }

  private def startDebugSession(): Future[Integer] = {
    server.executeCommand(ServerCommands.StartDebugSession.id).flatMap {
      case value: Integer =>
        Future(value)
      case value =>
        Future.failed(new IllegalStateException("Not a port number: " + value))
    }
  }
}
