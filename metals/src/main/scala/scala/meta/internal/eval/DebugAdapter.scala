package scala.meta.internal.eval
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient

trait DebugAdapter {
  def setClient(client: IDebugProtocolClient): Unit

  def shutdown(): Unit
}
