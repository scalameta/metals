package tests

import scala.meta.internal.metals.Icons
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.ModuleStatus
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.StatusBarConfig
import scala.meta.internal.metals.clients.language.MetalsStatusParams
import scala.meta.internal.metals.clients.language.StatusType

class ModuleStatusSuite extends BaseLspSuite("bsp-status-suite") {
  override def icons: Icons = Icons.unicode
  override def serverConfig: MetalsServerConfig =
    MetalsServerConfig.default.copy(moduleStatusBar = StatusBarConfig.on)

  test("module-status-bar") {
    cleanWorkspace()
    val mainPath = "a/src/main/scala/Main.scala"
    val barPath = "b/src/main/scala/Bar.scala"
    val noBuildTargetPath = "c/src/main/scala/NoBuildTarget.scala"
    for {
      _ <- initialize(
        s"""|/metals.json
            |{
            |  "a": {},
            |  "b": {"dependsOn": ["a"]}
            |}
            |
            |/$mainPath
            |package a
            |object Main {
            |  val x: Int = 1
            |}
            |/$barPath
            |package b
            |object Bar {
            |  val x: Int = 1
            |}
            |/$noBuildTargetPath
            |package b
            |object NoBuildTarget {
            |  val x: Int = 1
            |}
            |""".stripMargin
      )
      // should show ok status when no issues
      _ <- server.didOpen(mainPath)
      _ <- server.headServer.indexingPromise.future
      _ = assertNoDiagnostics()
      _ = assestModuleStatus(ModuleStatus.ok("a", icons), latestOnly = true)
      _ = client.getStatusParams(StatusType.module).clear()
      // should update status on open
      _ <- server.didOpen(barPath)
      _ = assestModuleStatus(ModuleStatus.ok("b", icons))
      // should correctly show no build target
      _ <- server.didOpen(noBuildTargetPath)
      _ = assestModuleStatus(ModuleStatus.noBuildTarget(icons))
      // should correctly show upstream compilation issues
      _ <- server.didOpen(mainPath)
      _ = assestModuleStatus(ModuleStatus.ok("a", icons))
      _ <- server.didChange(mainPath)(_ => """|package a
                                              |object Main {
                                              |  val x: String = yyy
                                              |}
                                              |""".stripMargin)
      _ <- server.didSave(mainPath)
      _ <- server.didOpen(barPath)
      _ = assestModuleStatus(
        ModuleStatus.upstreamCompilatonIssues("b", "a", icons)
      )
      _ = client.getStatusParams(StatusType.module).clear()
      // should update on report creation
      _ <- server.didFocus(mainPath)
      _ = assestModuleStatus(ModuleStatus.ok("a", icons))
      _ <- server.definition(
        mainPath,
        """|package a
           |object Main {
           |  val x: String = y@@yy
           |}
           |""".stripMargin,
        workspace,
      )
      bt = server.server.buildTargets
        .inverseSources(server.toPath(mainPath))
        .get
      _ = assestModuleStatus(ModuleStatus.warnings("a", bt, 1, icons))
      // should update on go to reports (clear reports)
      _ <- server.executeCommand(
        ServerCommands.ShowReportsForBuildTarget,
        bt.getUri(),
      )
      _ = assestModuleStatus(ModuleStatus.ok("a", icons))
      _ <- server.didChange(mainPath)(_ => """|package a
                                              |object Main {
                                              |}
                                              |""".stripMargin)
      // should update on upstream compilation finish
      saveMain = server.didSave(mainPath)
      _ <- server.didFocus(barPath)
      _ <- saveMain
      _ = assertNoDiagnostics()
      _ = assestModuleStatus(ModuleStatus.ok("b", icons), latestOnly = true)
    } yield ()
  }

  private def assestModuleStatus(
      status: MetalsStatusParams,
      latestOnly: Boolean = false,
  ) = {
    assertNoDiff(
      if (latestOnly) client.latestStatusBar(StatusType.module)
      else client.pollStatusBar(StatusType.module),
      status.text,
    )
  }

}
