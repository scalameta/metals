package tests

import scala.meta.internal.metals.InitializationOptions

class BuildIssueLspSuite extends BaseLspSuite(s"quick-build") {

  override protected def initializationOptions: Option[InitializationOptions] =
    Some(TestingServer.TestDefault)

  override protected val changeSpacesToDash: Boolean = false

  test("basic spaces", withoutVirtualDocs = true) {
    for {
      _ <- initialize(
        """
          |/metals.json
          |{
          |  "a": {}
          |}
          |/a/src/main/scala/a/A.scala
          |package a
          |import scala.util.Success
          |object A {
          |  val name: Int = "ABC"
          |}
        """.stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ = assertNoDiff(
        server.workspaceDefinitions,
        """|/a/src/main/scala/a/A.scala
           |package a
           |import scala.util.Success/*Try.scala*/
           |object A/*L2*/ {
           |  val name/*L3*/: Int/*Int.scala*/ = "ABC"
           |}
           |""".stripMargin,
      )
    } yield ()
  }
}
