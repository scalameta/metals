package scala.meta.metals

import scala.meta.internal.metals.{MetalsLspService => MetalsLspService}

/**
 * According to the spec, the server waits for the `initialize` request to be
 * sent. After that, the server sends the `initialized` notification to the
 * client. Next, the server is fully working and can receive requests and
 * notifications from the client.
 */
sealed trait ServerState
object ServerState {
  case object Started extends ServerState
  final case class Initialized(service: MetalsLspService) extends ServerState
  final case class ShuttingDown(service: MetalsLspService) extends ServerState
}
