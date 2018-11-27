package tests

import scala.meta.internal.metals.BloopProtocol

object TcpSlowSuite extends BaseSlowSuite("tcp") {
  override def protocol: BloopProtocol = BloopProtocol.tcp
  testAsync("tcp") {
    for {
      _ <- server.initialize(
        """
          |/metals.json
          |{
          |  "a": {}
          |}
          |/a/src/main/scala/A.scala
          |object A
        """.stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/A.scala")
      _ = assertNoDiff(client.workspaceErrorShowMessages, "")
    } yield ()
  }
}
