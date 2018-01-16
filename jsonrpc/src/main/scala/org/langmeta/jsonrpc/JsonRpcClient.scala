package org.langmeta.jsonrpc

import scala.concurrent.Future
import io.circe.Decoder
import io.circe.Encoder
import monix.eval.Task
import monix.execution.Ack

trait JsonRpcClient {
  final def notify[A](
      endpoint: Endpoint[A, Unit],
      notification: A
  ): Future[Ack] = notify[A](endpoint.method, notification)(endpoint.encoderA)
  def notify[A: Encoder](method: String, notification: A): Future[Ack]
  def serverRespond(response: Response): Future[Ack]
  def clientRespond(response: Response): Unit
  final def request[A, B](
      endpoint: Endpoint[A, B],
      req: A
  ): Task[Either[Response.Error, B]] =
    request[A, B](endpoint.method, req)(endpoint.encoderA, endpoint.decoderB)
  def request[A: Encoder, B: Decoder](
      method: String,
      request: A
  ): Task[Either[Response.Error, B]]
}

object JsonRpcClient {
  val empty: JsonRpcClient = new JsonRpcClient {
    override def notify[A: Encoder](
        method: String,
        notification: A
    ): Future[Ack] = Ack.Continue
    override def serverRespond(response: Response): Future[Ack] = Ack.Continue
    override def clientRespond(response: Response): Unit = ()
    override def request[A: Encoder, B: Decoder](
        method: String,
        request: A
    ): Task[Either[Response.Error, B]] = Task.never
  }
}
