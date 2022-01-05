package tests

import scala.jdk.CollectionConverters._

import scala.meta.internal.metals.Messages._
import scala.meta.internal.metals.ServerCommands

import bill.Bill

class BspSwitchLspSuite extends BaseLspSuite("bsp-switch") {

  test("switch".flaky) {
    cleanWorkspace()
    Bill.installWorkspace(workspace.toNIO)
    for {
      _ <- initialize("")
      _ = {
        client.messageRequests.clear()
        assertConnectedToBuildServer("Bill")
      }
      _ <- server.executeCommand(ServerCommands.BspSwitch)
      _ = {
        assertConnectedToBuildServer("Bill")
        assertNoDiff(
          client.workspaceShowMessages,
          BspSwitch.onlyOneServer("Bill").getMessage()
        )
      }
      _ = {
        client.messageRequests.clear()
        assertConnectedToBuildServer("Bill")
        Bill.installWorkspace(workspace.toNIO, "Bob")
      }
      _ <- server.executeCommand(ServerCommands.ConnectBuildServer)
      _ = {
        assertConnectedToBuildServer("Bob")
        assertNoDiff(
          client.workspaceMessageRequests,
          BspSwitch.message
        )
        assertNoDiff(client.workspaceShowMessages, "")

        client.messageRequests.clear()
        client.showMessageRequestHandler = { params =>
          params.getActions.asScala.find(_.getTitle == "Bill")
        }
      }
      _ <- server.executeCommand(ServerCommands.BspSwitch)
      _ = {
        assertNoDiff(client.workspaceShowMessages, "")
        assertConnectedToBuildServer("Bill")
      }
    } yield ()
  }
}
