package tests

import java.util.Collections.emptyList

import scala.concurrent.Future

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.debug.DebugStep._
import scala.meta.internal.metals.debug.DebugWorkspaceLayout
import scala.meta.internal.metals.debug.StepNavigator
import scala.meta.internal.metals.debug.Stoppage
import scala.meta.internal.metals.debug.TestDebugger

import ch.epfl.scala.bsp4j.DebugSessionParamsDataKind
import ch.epfl.scala.bsp4j.ScalaMainClass
import munit.Location
import munit.TestOptions
import org.eclipse.lsp4j.debug.SetBreakpointsResponse

abstract class BaseDapSuite(
    suiteName: String,
    initializer: BuildServerInitializer,
    buildToolLayout: BuildToolLayout,
) extends BaseLspSuite(suiteName, initializer) {
  override def serverConfig: MetalsServerConfig =
    super.serverConfig.copy(loglevel = "debug")

  override def beforeEach(context: BeforeEach): Unit = {
    super.beforeEach(context)
    dapClient.touch()
    dapServer.touch()
    bspTrace.touch()
  }

  def debugMain(
      buildTarget: String,
      main: String,
      stoppageHandler: Stoppage.Handler = Stoppage.Handler.Continue,
      requestOtherThreadStackTrace: Boolean = false,
  ): Future[TestDebugger] = {
    val kind = DebugSessionParamsDataKind.SCALA_MAIN_CLASS
    val mainClass = new ScalaMainClass(main, emptyList(), emptyList())
    server.startDebugging(
      buildTarget,
      kind,
      mainClass,
      stoppageHandler,
      requestOtherThreadStackTrace,
    )
  }

  def setBreakpoints(
      debugger: TestDebugger,
      workspace: DebugWorkspaceLayout,
  ): Future[List[SetBreakpointsResponse]] = {
    Future.sequence {
      workspace.filesBreakpoints
        .filter(_.breakpoints.nonEmpty)
        .map { file =>
          debugger.setBreakpoints(file.source, file.breakpoints)
        }
    }
  }

  def removeBreakpoints(
      debugger: TestDebugger,
      workspace: DebugWorkspaceLayout,
  ): Future[List[SetBreakpointsResponse]] = {
    Future.sequence {
      workspace.filesBreakpoints
        .filter(_.breakpoints.nonEmpty)
        .map { file =>
          debugger.setBreakpoints(file.source, Nil)
        }
    }
  }

  def scalaVersion = BuildInfo.scalaVersion

  def assertBreakpoints(
      name: TestOptions,
      main: Option[String] = None,
      buildTarget: Option[String] = None,
  )(
      source: String
  )(implicit loc: Location): Unit = {
    assertBreakpoints(
      name,
      navigator =>
        debugMain(
          buildTarget.getOrElse("a"),
          main.getOrElse("a.Main"),
          navigator,
        ),
    )(source)
  }

  def assertBreakpoints(
      name: TestOptions,
      createDebugger: StepNavigator => Future[TestDebugger],
  )(
      source: String
  )(implicit loc: Location): Unit = {
    test(name) {
      cleanWorkspace()
      val debugLayout = DebugWorkspaceLayout(source, workspace)
      val workspaceLayout = buildToolLayout(debugLayout.toString, scalaVersion)
      val navigator = navigateExpectedBreakpoints(debugLayout)

      for {
        _ <- initialize(workspaceLayout)
        _ = assertNoDiagnostics()
        debugger <- createDebugger(navigator)
        _ <- debugger.initialize
        _ <- debugger.launch(debug = true)
        _ <- setBreakpoints(debugger, debugLayout)
        _ <- debugger.configurationDone
        _ <- debugger.shutdown
      } yield ()
    }
  }
  def navigateExpectedBreakpoints(
      workspaceLayout: DebugWorkspaceLayout
  ): StepNavigator = {

    val expectedBreakpoints = workspaceLayout.filesBreakpoints.flatMap { file =>
      file.breakpoints.map(line => Breakpoint(file.sourcePath, line))
    }

    expectedBreakpoints.foldLeft(StepNavigator(workspace)) {
      (navigator, breakpoint) =>
        navigator.at(breakpoint.path, breakpoint.line + 1)(Continue)
    }
  }

}

private final case class Breakpoint(path: String, line: Int)
