package langserver.core

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors

import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import com.dhpcs.jsonrpc._
import com.typesafe.scalalogging.LazyLogging

import langserver.messages._
import langserver.types._
import play.api.libs.json._

/**
 * A connection that reads and writes Language Server Protocol messages.
 *
 * @note Commands are executed asynchronously via a thread pool
 * @note Notifications are executed synchronously on the calling thread
 * @note The command handler returns Any because sometimes response objects can't be part
 *       of a sealed hierarchy. For instance, goto definition returns a {{{Seq[Location]}}}
 *       and that can't subclass anything other than Any
 */
class Connection(inStream: InputStream, outStream: OutputStream)(val commandHandler: (String, ServerCommand) => Any)
    extends LazyLogging {
  private val msgReader = new MessageReader(inStream)
  private val msgWriter = new MessageWriter(outStream)

  // 4 threads should be enough for everyone
  implicit private val commandExecutionContext = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(4))

  val notificationHandlers: ListBuffer[Notification => Unit] = ListBuffer.empty

  def notifySubscribers(n: Notification): Unit = {
    notificationHandlers.foreach(f =>
      Try(f(n)).recover { case e => logger.error("failed notification handler", e) })
  }

  def sendNotification(params: Notification): Unit = {
    val json = Notification.write(params)
    msgWriter.write(json)
  }

  /**
   * A notification sent to the client to show a message.
   *
   * @param tpe One of MessageType values
   * @param message The message to display in the client
   */
  def showMessage(tpe: Int, message: String): Unit = {
    sendNotification(ShowMessageParams(tpe, message))
  }

  def showMessage(tpe: Int, message: String, actions: String*): Unit = {
    ???
  }

  /**
   * The log message notification is sent from the server to the client to ask
   * the client to log a particular message.
   *
   * @param tpe One of MessageType values
   * @param message The message to display in the client
   */
  def logMessage(tpe: Int, message: String): Unit = {
    sendNotification(LogMessageParams(tpe, message))
  }

  /**
   * Publish compilation errors for the given file.
   */
  def publishDiagnostics(uri: String, diagnostics: Seq[Diagnostic]): Unit = {
    sendNotification(PublishDiagnostics(uri, diagnostics))
  }

  def start() {
    var streamClosed = false
    do {
      msgReader.nextPayload() match {
        case None => streamClosed = true

        case Some(jsonString) =>
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
                  case (Some(id), Right(command)) => handleCommand(request.method, id, command)
                }

              case response: JsonRpcResponseMessage =>
                logger.info(s"Received response: $response")

              case m =>
                logger.error(s"Received unknown message: $m")
            }
          }
      }
    } while (!streamClosed)
  }

  private def readJsonRpcMessage(jsonString: String): Either[JsonRpcResponseError, JsonRpcMessage] = {
    logger.debug(s"Received $jsonString")
    Try(Json.parse(jsonString)) match {
      case Failure(exception) =>
        Left(JsonRpcResponseError.parseError(exception))

      case Success(json) =>
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

  private def handleCommand(method: String, id: Either[String, BigDecimal], command: ServerCommand) = {
    Future(commandHandler(method, command)).map { result =>
      msgWriter.write(ResultResponse.write(Right(result), Some(id)))
    }
  }
}
