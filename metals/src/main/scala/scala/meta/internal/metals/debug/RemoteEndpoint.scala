package scala.meta.internal.metals.debug

import scala.meta.internal.metals.Cancelable

import org.eclipse.lsp4j.jsonrpc.MessageConsumer
import org.eclipse.lsp4j.jsonrpc.MessageProducer

trait RemoteEndpoint
    extends MessageConsumer
    with MessageProducer
    with Cancelable
