package tests

import java.util.Collections.emptyList
import ch.epfl.scala.bsp4j.DebugSessionParamsDataKind
import ch.epfl.scala.bsp4j.ScalaMainClass
import org.eclipse.lsp4j.debug.SetBreakpointsResponse
import scala.concurrent.Future
import scala.meta.internal.metals.debug.DebugFileLayout
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
      files: List[DebugFileLayout]
  ): Future[List[SetBreakpointsResponse]] = {
    Future.sequence {
      files.map { file =>
        val path = server.toPath(file.relativePath)
        debugger.setBreakpoints(path, file.breakpoints)
      }
    }
  }
}
