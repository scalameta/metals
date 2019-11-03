package scala.meta.internal.worksheets

import scala.meta.internal.decorations.DecorationOptions
import scala.meta.io.AbsolutePath
import scala.meta.pc.CancelToken
import scala.concurrent.Future

trait WorksheetProvider {
  def decorations(
      path: AbsolutePath,
      token: CancelToken
  ): Future[Array[DecorationOptions]]
}
