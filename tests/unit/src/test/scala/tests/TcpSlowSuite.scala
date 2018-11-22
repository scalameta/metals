package tests

import scala.meta.internal.metals.BloopProtocol

object TcpSlowSuite extends BaseSlowSuite("tcp") {
  override def protocol: BloopProtocol = BloopProtocol.tcp
  testAsync("tcp") {
    for {
      _ <- server.initialize(
        """
          |/project/build.properties
          |sbt.version=1.2.3
          |/build.sbt
          |scalaVersion := "2.12.7"
        """.stripMargin
      )
      _ = assertNoDiff(client.workspaceErrorShowMessages, "")
    } yield ()
  }
}
