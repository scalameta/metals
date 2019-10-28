package scala.meta.internal.metals.debug

import org.eclipse.lsp4j.jsonrpc.MessageConsumer
import org.eclipse.lsp4j.jsonrpc.MessageProducer
import scala.meta.internal.metals.Cancelable

trait RemoteEndpoint
    extends MessageConsumer
    with MessageProducer
    with Cancelable
