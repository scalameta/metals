package tests.remotels

import scala.meta.internal.metals.UserConfiguration

class RemoteLanguageServerLspSuite extends tests.BaseLspSuite("remotels") {

  private val remote = TestingRemoteLanguageServer()

  override def beforeAll(): Unit = remote.start()
  override def afterAll(): Unit = remote.stop()
  override def userConfig: UserConfiguration =
    super.userConfig.copy(remoteLanguageServer = Some(remote.address))

  test("basic") {
    for {
      _ <- server.initialize(
        """|/metals.json
           |{
           |  "a": { }
           |}
           |/Foo.scala
           |object Foo {
           |  val x = 42
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("Foo.scala")
      _ = assertNoDiff(
        server.workspaceDefinitions,
        """|/Foo.scala
           |object Foo/*L0*/ {
           |  val x/*L1*/ = 42
           |}
           |""".stripMargin
      )
    } yield ()
  }

}
