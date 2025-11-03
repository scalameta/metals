package tests.mbt

import java.nio.file.Paths

import scala.meta.internal.metals.Configs
import scala.meta.internal.metals.MetalsEnrichments.XtensionAbsolutePathBuffers
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.StatisticsConfig
import scala.meta.internal.metals.UserConfiguration

// Comment out to run manual tests
@munit.IgnoreSuite
class MbtManualSuite extends tests.BaseManualSuite {

  override def defaultUserConfig: UserConfiguration =
    super.defaultUserConfig.copy(
      workspaceSymbolProvider = Configs.WorkspaceSymbolProviderConfig.mbt2
    )
  override def defaultMetalsServerConfig: MetalsServerConfig =
    super.defaultMetalsServerConfig.copy(
      statistics = StatisticsConfig.workspaceSymbol
    )
  def repo(name: String): String =
    Paths.get(System.getProperty("user.home"), name).toString()

  inDirectory(repo("jmin"), removeCache = false).test("small") {
    case (server, _) =>
      val main = "a/src/main/scala/example/Main.scala"
      val foobar = "a/src/main/scala/example/FoobarQux.scala"
      server.workspace.resolve(foobar).deleteIfExists()
      for {
        _ <- server.didOpen(main)
        _ = assertNoDiff(
          server.workspaceSymbol("FoobarQux"),
          "",
        )
        _ = server.workspace.resolve(foobar).touch()
        _ <- server.didOpen(foobar)
        _ <- server.didChange(foobar)(_ =>
          "package example; class FoobarQux { def qux() = println(\"Hello, World!\") }"
        )
        _ <- server.didSave(foobar)
        _ = assertNoDiff(
          server.workspaceSymbol("FoobarQux"),
          "example.FoobarQux",
        )
      } yield ()
  }

  inDirectory(repo("runtime"), removeCache = true).test("medium") {
    case (server, _) =>
      for {
        _ <- server.didOpen(
          "example/Example.scala"
        )
        _ = assertNoDiff(
          server.workspaceSymbol("Example"),
          """|Class Example com.example com.
             |""".stripMargin,
        )
      } yield ()
  }

  inDirectory(repo("universe"), removeCache = false).test("large") {
    case (server, _) =>
      for {
        _ <- server.didOpen(
          "example/Example.scala"
        )
        _ = assertNoDiff(
          server.workspaceSymbol("Example"),
          """|Class Example com.example com.
             |""".stripMargin,
        )
      } yield ()
  }
}
