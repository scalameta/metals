package scala.meta.internal.metals

import java.util.concurrent.CancellationException
import org.eclipse.lsp4j.jsonrpc.CancelChecker
import scala.meta.pc.OffsetParams

case class CompilerOffsetParams(
    filename: String,
    text: String,
    offset: Int,
    token: CancelChecker = EmptyCancelChecker
) extends OffsetParams {
  override def checkCanceled(): Unit = {
    try token.checkCanceled()
    catch {
      case e: CancellationException =>
        throw new ControlCancellationException(e)
    }
  }
}
