package scala.meta.internal.metals

import java.util.concurrent.CompletableFuture

import scala.annotation.nowarn
import scala.build.bsp.ScalaScriptBuildServer

import scala.meta.internal.bsp.sync.SyncBuildServer

import ch.epfl.scala.{bsp4j => b}

trait MetalsBuildServer
    extends b.BuildServer
    with b.ScalaBuildServer
    with b.JavaBuildServer
    with b.JvmBuildServer
    with ScalaScriptBuildServer
    with SyncBuildServer {

  @nowarn
  def debugSessionStart(
      params: b.DebugSessionParams,
      noDebug: Boolean,
  ): CompletableFuture[b.DebugSessionAddress] = {
    CompletableFuture.failedFuture(
      new UnsupportedOperationException(
        "MBT build server does not support the default 'debugSessionStart'."
      )
    )
  }
}
