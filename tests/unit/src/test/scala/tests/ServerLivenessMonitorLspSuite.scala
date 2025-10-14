package tests

import scala.concurrent.duration.Duration

import scala.meta.internal.bsp.ConnectionBspStatus
import scala.meta.internal.metals.Icons
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.StatusBarConfig

import bill.Bill
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.ScalaMainClassesParams

class ServerLivenessMonitorLspSuite extends BaseLspSuite("liveness-monitor") {
  val pingInterval: Duration = Duration("3s")
  override def serverConfig: MetalsServerConfig =
    MetalsServerConfig.default.copy(
      metalsToIdleTime = Duration("3m"),
      pingInterval = pingInterval,
      bspStatusBar = StatusBarConfig.showMessage,
    )

  test("handle-not-responding-server") {
    val sleepTime = pingInterval.toMillis * 4
    cleanWorkspace()
    Bill.installWorkspace(workspace)

    def isServerResponsive =
      server.headServer.doctor.buildTargetsJson().header.isBuildServerResponsive
    for {
      _ <- initialize(
        """
          |/src/com/App.scala
          |object App {
          |  val x: Int = 4
          |}
        """.stripMargin
      )
      _ <- server.didOpen("src/com/App.scala")
      _ = Thread.sleep(sleepTime)
      _ <- server.didChange("src/com/App.scala")(str => s"""|$str
                                                            |
                                                            |object O {
                                                            | def i: Int = 3
                                                            |}
                                                            |""".stripMargin)
      _ <- server.didSave("src/com/App.scala")
      _ = Thread.sleep(sleepTime)
      _ = assertNoDiff(
        server.client.workspaceMessageRequests,
        Messages.CheckDoctor.allProjectsMisconfigured,
      )
      _ <- server.server.bspSession.get.main.mainClasses(
        new ScalaMainClassesParams(
          List(
            new BuildTargetIdentifier("break"),
            new BuildTargetIdentifier(
              (pingInterval * 6).toString()
            ),
          ).asJava
        )
      )
      _ = Thread.sleep(sleepTime)
      noResponseParams = ConnectionBspStatus.noResponseParams(
        "Bill",
        Icons.default,
      )
      _ = assertContains(
        server.client.workspaceMessageRequests,
        Messages.CheckDoctor.allProjectsMisconfigured,
      )
      _ = assertContains(
        server.client.workspaceMessageRequests,
        noResponseParams.logMessage(Icons.default),
      )
      _ = assertEquals(isServerResponsive, Some(false))
      _ = Thread.sleep(sleepTime)
      _ = assertEquals(isServerResponsive, Some(true))
    } yield ()
  }
}
