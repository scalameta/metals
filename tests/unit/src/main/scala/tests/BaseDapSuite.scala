package tests

import java.util.Collections.emptyList
import ch.epfl.scala.bsp4j.DebugSessionParamsDataKind
import ch.epfl.scala.bsp4j.ScalaMainClass
import org.eclipse.lsp4j.debug.SetBreakpointsResponse
import scala.concurrent.Future
import scala.meta.internal.metals.debug.DebugWorkspaceLayout
import scala.meta.internal.metals.debug.Stoppage
import scala.meta.internal.metals.debug.TestDebugger

abstract class BaseDapSuite(suiteName: String) extends BaseLspSuite(suiteName) {
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
