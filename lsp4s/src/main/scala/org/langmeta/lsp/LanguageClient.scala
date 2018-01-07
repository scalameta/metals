package org.langmeta.lsp

import java.io.OutputStream
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.Duration
import scala.util.Try
import cats.syntax.either._
import com.typesafe.scalalogging.Logger
import io.circe.Decoder
import io.circe.Encoder
import io.circe.syntax._
import monix.eval.Callback
import monix.eval.Task
import monix.execution.Cancelable
import monix.execution.atomic.Atomic
import monix.execution.atomic.AtomicInt
import org.langmeta.jsonrpc.JsonRpcClient
import org.langmeta.jsonrpc.MessageWriter
import org.langmeta.jsonrpc.Notification
import org.langmeta.jsonrpc.Request
import org.langmeta.jsonrpc.RequestId
import org.langmeta.jsonrpc.Response

class LanguageClient(out: OutputStream, logger: Logger) extends JsonRpcClient {
  private val writer = new MessageWriter(out, logger)
  private val counter: AtomicInt = Atomic(1)
  private val activeServerRequests =
    TrieMap.empty[RequestId, Callback[Response]]
  def notify[A: Encoder](method: String, notification: A): Unit =
    writer.write(Notification(method, Some(notification.asJson)))
  def serverRespond(response: Response): Unit = response match {
    case Response.Empty => ()
    case x: Response.Success => writer.write(x)
    case x: Response.Error =>
      logger.error(s"Response error: $x")
      writer.write(x)
  }
  def clientRespond(response: Response): Unit =
    for {
      id <- response match {
        case Response.Empty => None
        case Response.Success(_, requestId) => Some(requestId)
        case Response.Error(_, requestId) => Some(requestId)
      }
      callback <- activeServerRequests.get(id).orElse {
        val fallback = for {
          string <- id.asJson.asString
          number <- Try(string.toInt).toOption
          request <- activeServerRequests.get(RequestId(number))
        } yield {
          // seems to be necessary for sbt-server :(
          logger.info(s"Expected int response id, got string $id")
          request
        }
        fallback.orElse {
          logger.error(s"Response to unknown request: $response")
          None
        }
      }
    } {
      activeServerRequests.remove(id)
      callback.onSuccess(response)
    }

  def request[A: Encoder, B: Decoder](
      method: String,
      request: A
  ): Task[Either[Response.Error, B]] = {
    val nextId = RequestId(counter.incrementAndGet())
    val response = Task.create[Response] { (out, cb) =>
      val scheduled = out.scheduleOnce(Duration(0, "s")) {
        val json = Request(method, Some(request.asJson), nextId)
        activeServerRequests.put(nextId, cb)
        writer.write(json)
      }
      Cancelable { () =>
        scheduled.cancel()
        this.notify("$/cancelRequest", CancelParams(nextId.value))
      }
    }
    response.map {
      case Response.Empty =>
        Left(
          Response.invalidParams(
            s"Got empty response for request $request",
            nextId
          )
        )
      case err: Response.Error =>
        Left(err)
      case Response.Success(result, _) =>
        result.as[B].leftMap { err =>
          Response.invalidParams(err.toString, nextId)
        }
    }
  }
}
