package langserver.core

import java.io.OutputStream
import java.io.InputStream
import com.dhpcs.jsonrpc._
import langserver.messages.ServerCommand
import scala.util.Try
import play.api.libs.json._
import scala.util.Failure
import scala.util.Success
import scala.collection.mutable.ListBuffer
import langserver.messages.Notification
import com.typesafe.scalalogging.LazyLogging
import langserver.messages.Response
import langserver.messages.ResultResponse
import scala.concurrent.Future
import scala.concurrent.ExecutionContext
import java.util.concurrent.Executors

/**
 * A connection that reads and writes Language Server Protocol messages.
 *
 * @note Commands are executed asynchronously via a thread pool
 * @note Notifications are executed synchronously on the calling thread
 */
class ConnectionImpl(inStream: InputStream, outStream: OutputStream)(val commandHandler: ServerCommand => ResultResponse) extends LazyLogging {
  private val msgReader = new MessageReader(inStream)
  private val msgWriter = new MessageWriter(outStream)

  // 4 threads should be enough for everyone
  implicit private val commandExecutionContext = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(4))

  val notificationHandlers: ListBuffer[Notification => Unit] = ListBuffer.empty

  def notifySubscribers(n: Notification): Unit = {
    notificationHandlers.foreach(f =>
      Try(f(n)).recover { case e => logger.error("failed notification handler", e) }
    )
  }

  def sendNotification(params: Notification): Unit = {
    val json = Notification.write(params)
    msgWriter.write(json)
  }

  def start() {
    while (true) {
      val jsonString = msgReader.nextPayload()

      readJsonRpcMessage(jsonString) match {
        case Left(e) =>
          msgWriter.write(e)

        case Right(message) => message match {
          case notification: JsonRpcNotificationMessage =>
            Notification.read(notification).fold {
              logger.error(s"No notification type exists with method=${notification.method}")
            }(_.fold({ errors => logger.error(s"Invalid Notification: $errors") },
              notifySubscribers))

          case request: JsonRpcRequestMessage =>
            unpackRequest(request) match {
              case (_, Left(e)) => msgWriter.write(e)
              case (None, Right(c)) => // this is disallowed by the language server specification
                logger.error(s"Received request without 'id'. $c")
              case (Some(id), Right(command)) => handleCommand(id, command)
            }

          case response: JsonRpcResponseMessage =>
            logger.info(s"Received response: $response")

          case m =>
            logger.error(s"Received unknown message: $m")
        }
      }
    }
  }

  private def readJsonRpcMessage(jsonString: String): Either[JsonRpcResponseError, JsonRpcMessage] = {
    logger.debug(s"Received $jsonString")
    Try(Json.parse(jsonString)) match {
      case Failure(exception) =>
        Left(JsonRpcResponseError.parseError(exception))

      case Success(json) =>
        logger.info(s"Received: ${Json.prettyPrint(json)}")
        Json.fromJson[JsonRpcMessage](json).fold({ errors =>
          Left(JsonRpcResponseError.invalidRequest(errors))
        }, Right(_))
    }
  }

  private def readCommand(jsonString: String): (Option[Either[String, BigDecimal]], Either[JsonRpcResponseError, ServerCommand]) =
    Try(Json.parse(jsonString)) match {
      case Failure(exception) =>
        None -> Left(JsonRpcResponseError.parseError(exception))

      case Success(json) =>
        Json.fromJson[JsonRpcRequestMessage](json).fold(
          errors => None -> Left(JsonRpcResponseError.invalidRequest(errors)),

          jsonRpcRequestMessage =>
            ServerCommand.read(jsonRpcRequestMessage)
              .fold[(Option[Either[String, BigDecimal]], Either[JsonRpcResponseError, ServerCommand])](
                jsonRpcRequestMessage.id -> Left(JsonRpcResponseError.methodNotFound(jsonRpcRequestMessage.method)))(commandJsResult => commandJsResult.fold(
                  errors => jsonRpcRequestMessage.id -> Left(JsonRpcResponseError.invalidParams(errors)),
                  command => jsonRpcRequestMessage.id -> Right(command))))

    }

  private def unpackRequest(request: JsonRpcRequestMessage): (Option[Either[String, BigDecimal]], Either[JsonRpcResponseError, ServerCommand]) = {
    ServerCommand.read(request)
      .fold[(Option[Either[String, BigDecimal]], Either[JsonRpcResponseError, ServerCommand])](

        request.id -> Left(
          JsonRpcResponseError.methodNotFound(request.method)))(commandJsResult => commandJsResult.fold(
          errors => request.id -> Left(JsonRpcResponseError.invalidParams(errors)),
          command => request.id -> Right(command)))
  }

  private def handleCommand(id: Either[String,BigDecimal], command: ServerCommand) = {
    logger.info(s"Received commmand: $id -- $command")
    Future(commandHandler(command)).map { result =>
      msgWriter.write(ResultResponse.write(Right(result), Some(id)))
    }
  }
}
