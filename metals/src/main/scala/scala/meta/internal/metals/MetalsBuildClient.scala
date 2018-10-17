package scala.meta.internal.metals

import ch.epfl.scala.bsp4j.BuildClient
import ch.epfl.scala.bsp4j.CancelFileWatcherParams
import ch.epfl.scala.bsp4j.CancelFileWatcherResult
import ch.epfl.scala.bsp4j.CompileReport
import ch.epfl.scala.bsp4j.DidChangeBuildTarget
import ch.epfl.scala.bsp4j.LogMessageParams
import ch.epfl.scala.bsp4j.PublishDiagnosticsParams
import ch.epfl.scala.bsp4j.RegisterFileWatcherParams
import ch.epfl.scala.bsp4j.RegisterFileWatcherResult
import ch.epfl.scala.bsp4j.ShowMessageParams
import java.util.concurrent.CompletableFuture

class MetalsBuildClient extends BuildClient {
  override def onBuildShowMessage(params: ShowMessageParams): Unit =
    scribe.info(params.toString)
  override def onBuildLogMessage(params: LogMessageParams): Unit =
    scribe.info(params.toString)
  override def onBuildPublishDiagnostics(
      params: PublishDiagnosticsParams
  ): Unit = scribe.info(params.toString)
  override def onBuildTargetDidChange(params: DidChangeBuildTarget): Unit =
    scribe.info(params.toString)
  override def buildRegisterFileWatcher(
      params: RegisterFileWatcherParams
  ): CompletableFuture[
    RegisterFileWatcherResult
  ] = {
    scribe.info(params.toString)
    null
  }
  override def buildCancelFileWatcher(
      params: CancelFileWatcherParams
  ): CompletableFuture[CancelFileWatcherResult] = {
    scribe.info(params.toString)
    null
  }
  override def onBuildTargetCompileReport(params: CompileReport): Unit =
    scribe.info(params.toString)
}
