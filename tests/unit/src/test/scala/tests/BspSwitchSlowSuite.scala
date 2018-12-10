package tests

import bill.Bill
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.Messages._

object BspSwitchSlowSuite extends BaseSlowSuite("bsp-switch") {
  testAsync("switch") {
    cleanWorkspace()
    Bill.installWorkspace(workspace.toNIO)
    for {
      _ <- server.initialize("")
      _ = {
        client.messageRequests.clear()
        assertConnectedToBuildServer("Bill")
        Bill.installWorkspace(workspace.toNIO, "Bob")
      }
      _ <- server.executeCommand(ServerCommands.ConnectBuildServer.id)
      _ = {
        assertConnectedToBuildServer("Bob")
        assertNoDiff(
          client.workspaceMessageRequests,
          SelectBspServer.message
        )
        assertNoDiff(client.workspaceShowMessages, "")

        client.messageRequests.clear()
        client.showMessageRequestHandler = { params =>
          params.getActions.asScala.find(_.getTitle == "Bill")
        }
      }
      _ <- server.executeCommand(ServerCommands.BspSwitch.id)
      _ = {
        assertNoDiff(client.workspaceShowMessages, "")
        assertConnectedToBuildServer("Bill")
      }
    } yield ()
  }
}
