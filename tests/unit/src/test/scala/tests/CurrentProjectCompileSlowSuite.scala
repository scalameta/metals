package tests
import scala.meta.internal.metals.UserConfiguration

object CurrentProjectCompileSlowSuite extends BaseSlowSuite("current-project") {
  override def userConfig: UserConfiguration = super.userConfig.copy(
    compileOnSave = UserConfiguration.CurrentProjectCompile
  )
  testAsync("basic") {
    for {
      _ <- server.initialize(
        """
          |/metals.json
          |{
          |  "a": { },
          |  "b": { "dependsOn": ["a"] }
          |}
          |/a/src/main/scala/a/A.scala
          |object A {}
          |/b/src/main/scala/b/B.scala
          |object B {
          |  val n: String = 42
          |}
        """.stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      // Assert that we don't trigger compilation in "b" even if it depends on "a".
      _ = assertNoDiff(client.workspaceDiagnostics, "")
    } yield ()
  }
}
