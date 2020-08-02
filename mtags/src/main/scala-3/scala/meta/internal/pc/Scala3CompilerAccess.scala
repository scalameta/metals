package scala.meta.internal.pc

import scala.meta.internal.pc.CompilerAccess
import scala.meta.internal.pc.CompilerWrapper
import scala.meta.internal.pc.ReporterAccess
import dotty.tools.dotc.interactive.InteractiveDriver
import dotty.tools.dotc.reporting.StoreReporter
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger
import scala.util.control.NonFatal
import scala.meta.pc.CancelToken
import scala.meta.pc.PresentationCompilerConfig
import java.util.concurrent.CompletableFuture
import scala.concurrent.ExecutionContextExecutor

class Scala3CompilerAccess(
    config: PresentationCompilerConfig,
    sh: Option[ScheduledExecutorService],
    newCompiler: () => Scala3CompilerWrapper
)(using ec: ExecutionContextExecutor)
    extends CompilerAccess[StoreReporter, InteractiveDriver](
      config,
      sh,
      newCompiler
    ) {

  def newReporter = new StoreReporter(null)

  protected def handleSharedCompilerException(
      t: Throwable
  ): Option[String] = {
    throw t
  }

  protected def ignoreException(t: Throwable): Boolean = false
}
