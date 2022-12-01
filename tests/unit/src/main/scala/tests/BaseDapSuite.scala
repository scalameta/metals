package tests

import java.util.Collections.emptyList

import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.Trace
import scala.meta.internal.metals.debug.DebugProtocol
import scala.meta.internal.metals.debug.DebugStep._
import scala.meta.internal.metals.debug.DebugWorkspaceLayout
import scala.meta.internal.metals.debug.StepNavigator
import scala.meta.internal.metals.debug.Stoppage
import scala.meta.internal.metals.debug.TestDebugger

import ch.epfl.scala.bsp4j.DebugSessionParamsDataKind
import ch.epfl.scala.bsp4j.ScalaMainClass
import munit.GenericBeforeEach
import munit.Location
import munit.TestOptions
import org.eclipse.lsp4j.debug.SetBreakpointsResponse

abstract class BaseDapSuite(
    suiteName: String,
    initializer: BuildServerInitializer,
    buildToolLayout: BuildToolLayout,
) extends BaseLspSuite(suiteName, initializer) {

  private val dapClient = Trace.protocolTracePath(DebugProtocol.clientName)
  private val dapServer = Trace.protocolTracePath(DebugProtocol.serverName)

  override def beforeEach(context: GenericBeforeEach[Future[Any]]): Unit = {
    super.beforeEach(context)
    dapClient.touch()
    dapServer.touch()
  }

  protected def logDapTraces(): Unit = {
    if (isCI) {
      scribe.warn("The DAP test failed, printing the traces")
      scribe.warn(dapClient.toString() + ":\n" + dapClient.readText)
      scribe.warn(dapServer.toString() + ":\n" + dapServer.readText)
    }
  }

  override def munitTestTransforms: List[TestTransform] =
    super.munitTestTransforms :+
      new TestTransform(
        "Print DAP traces",
        { test =>
          test.withBody(() =>
            test
              .body()
              .andThen {
                case Failure(exception) =>
                  logDapTraces()
                  exception
                case Success(value) => value
              }(munitExecutionContext)
          )
        },
      )

  def debugMain(
      buildTarget: String,
      main: String,
      stoppageHandler: Stoppage.Handler = Stoppage.Handler.Continue,
  ): Future[TestDebugger] = {
    val kind = DebugSessionParamsDataKind.SCALA_MAIN_CLASS
    val mainClass = new ScalaMainClass(main, emptyList(), emptyList())
    server.startDebugging(buildTarget, kind, mainClass, stoppageHandler)
  }

  def setBreakpoints(
      debugger: TestDebugger,
      workspace: DebugWorkspaceLayout,
  ): Future[List[SetBreakpointsResponse]] = {
    Future.sequence {
      workspace.filesBreakpoints
        .filter(_.breakpoints.nonEmpty)
        .map { file =>
          debugger.setBreakpoints(file.path, file.breakpoints)
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
          debugger.setBreakpoints(file.path, Nil)
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
        _ <- debugger.launch
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
      file.breakpoints.map(line => Breakpoint(file.path.toString(), line))
    }

    expectedBreakpoints.foldLeft(StepNavigator(workspace)) {
      (navigator, breakpoint) =>
        navigator.at(breakpoint.path, breakpoint.line + 1)(Continue)
    }
  }

}

private final case class Breakpoint(path: String, line: Int)
