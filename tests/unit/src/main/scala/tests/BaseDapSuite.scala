package tests

import java.util.Collections.emptyList
import ch.epfl.scala.bsp4j.DebugSessionParamsDataKind
import ch.epfl.scala.bsp4j.ScalaMainClass
import org.eclipse.lsp4j.debug.SetBreakpointsResponse
import scala.concurrent.Future
import scala.meta.internal.metals.debug.DebugWorkspaceLayout
import scala.meta.internal.metals.debug.Stoppage
import scala.meta.internal.metals.debug.TestDebugger
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.GlobalTrace
import scala.meta.internal.metals.debug.DebugProtocol
import scala.util.Failure
import scala.util.Success
import munit.GenericBeforeEach

abstract class BaseDapSuite(suiteName: String) extends BaseLspSuite(suiteName) {

  private val dapClient =
    GlobalTrace.protocolTracePath(DebugProtocol.clientName)
  private val dapServer =
    GlobalTrace.protocolTracePath(DebugProtocol.serverName)

  override def beforeEach(context: GenericBeforeEach[Future[Any]]): Unit = {
    super.beforeEach(context)
    dapClient.touch()
    dapServer.touch()
  }

  protected def logDapTraces(): Unit = {
    scribe.warn("The DAP test failed, printing the traces")
    scribe.warn(dapClient.toString() + ":\n" + dapClient.readText)
    scribe.warn(dapServer.toString() + ":\n" + dapServer.readText)
  }

  override def munitTestTransforms: List[TestTransform] =
    super.munitTestTransforms :+
      new TestTransform("Print DAP traces", { test =>
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
      })

  def debugMain(
      buildTarget: String,
      main: String,
      stoppageHandler: Stoppage.Handler = Stoppage.Handler.Continue
  ): Future[TestDebugger] = {
    val kind = DebugSessionParamsDataKind.SCALA_MAIN_CLASS
    val mainClass = new ScalaMainClass(main, emptyList(), emptyList())
    server.startDebugging(buildTarget, kind, mainClass, stoppageHandler)
  }

  def setBreakpoints(
      debugger: TestDebugger,
      workspace: DebugWorkspaceLayout
  ): Future[List[SetBreakpointsResponse]] = {
    Future.sequence {
      workspace.files
        .filter(_.breakpoints.nonEmpty)
        .map { file =>
          val path = server.toPath(file.relativePath)
          debugger.setBreakpoints(path, file.breakpoints)
        }
    }
  }
}
