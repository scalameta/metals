package org.langmeta.jsonrpc

import io.circe.Decoder
import io.circe.Encoder
import monix.eval.Task

class Endpoint[A: Decoder: Encoder, B: Decoder: Encoder](val method: String) {
  def encoderA: Encoder[A] = implicitly
  def decoderA: Decoder[A] = implicitly
  def encoderB: Encoder[B] = implicitly
  def decoderB: Decoder[B] = implicitly

  def request(request: A)(
      implicit client: JsonRpcClient
  ): Task[Either[Response.Error, B]] =
    client.request[A, B](method, request)
  def notify(
      notification: A
  )(implicit client: JsonRpcClient, ev: B =:= Unit): Unit =
    client.notify[A](method, notification)
}

object Endpoint {
  def request[A: Decoder: Encoder, B: Decoder: Encoder](
      method: String
  ): Endpoint[A, B] =
    new Endpoint(method)
  def notification[A: Decoder: Encoder](method: String): Endpoint[A, Unit] =
    new Endpoint(method)
}
