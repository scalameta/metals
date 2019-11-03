package scala.meta.internal.worksheets

import scala.meta.internal.decorations.DecorationOptions
import scala.meta.io.AbsolutePath
import scala.concurrent.Future
import scala.meta.pc.CancelToken

object NoopWorksheetProvider extends WorksheetProvider {
  override def decorations(
      path: AbsolutePath,
      token: CancelToken
  ): Future[Array[DecorationOptions]] = Future.successful(Array.empty)
}
