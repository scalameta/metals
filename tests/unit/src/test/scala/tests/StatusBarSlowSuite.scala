package tests
import scala.meta.internal.metals.Icons

object StatusBarSlowSuite extends BaseSlowSuite("status-bar") {
  override def icons: Icons = Icons.vscode
  testAsync("compile-success") {
    for {
      _ <- server.initialize(
        """
          |/metals.json
          |{ "a": {} }
          |/a/src/main/scala/A.scala
          |object A {
          |  val x = 42
          |}
        """.stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/A.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        ""
      )
      // Successful compilation is not reported in the status bar if there was no prior compile error.
      _ = assertNotContains(
        server.statusBarHistory,
        s"${icons.check}Compiled a"
      )
      _ <- server.didSave("a/src/main/scala/A.scala")(
        _.replaceFirst("val x = 42", "val x: String = 42")
      )
      _ = assertNotEmpty(client.workspaceDiagnostics)
      // Failed compilation is always reported in the status bar.
      _ = assertContains(
        server.statusBarHistory,
        s"${icons.alert}Compiled a"
      )
      _ <- server.didSave("a/src/main/scala/A.scala")(
        _.replaceFirst("val x: String = 42", "val x = 42")
      )
      // Successful compilation is reported in the status bar when following a failed compilation.
      _ = assertContains(
        server.statusBarHistory,
        s"${icons.check}Compiled a"
      )
    } yield ()
  }
}
