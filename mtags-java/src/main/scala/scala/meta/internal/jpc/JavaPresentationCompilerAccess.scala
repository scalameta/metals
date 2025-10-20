package scala.meta.internal.jpc

import java.util.concurrent.ScheduledExecutorService

import scala.concurrent.ExecutionContextExecutor

import scala.meta.internal.pc.CompilerAccess
import scala.meta.pc.PresentationCompilerConfig

import org.slf4j.Logger

class JavaPresentationCompilerAccess(
    logger: Logger,
    config: PresentationCompilerConfig,
    sh: Option[ScheduledExecutorService],
    newCompiler: () => JavaMetalsCompilerWrapper,
    id: String = ""
)(implicit ec: ExecutionContextExecutor)
    extends CompilerAccess[Unit, JavaMetalsCompiler](
      logger,
      config,
      sh,
      newCompiler,
      shouldResetJobQueue = false,
      id
    ) {
  override protected def newReporter: Unit = ()
  override protected def handleSharedCompilerException(
      t: Throwable
  ): Option[String] = Some(s"an error in the Java compiler: ${t}")

  override protected def ignoreException(t: Throwable): Boolean = false

}
