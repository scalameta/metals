package scala.meta.internal.worksheets

import scala.meta.internal.decorations.DecorationOptions
import scala.meta.io.AbsolutePath
import scala.meta.pc.CancelToken
import scala.concurrent.Future
import ch.epfl.scala.bsp4j.BuildTargetIdentifier

trait WorksheetProvider {
  def reset(): Unit
  def onBuildTargetDidCompile(target: BuildTargetIdentifier): Unit
  def decorations(
      path: AbsolutePath,
      token: CancelToken
  ): Future[Array[DecorationOptions]]
}
