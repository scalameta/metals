package langserver.core

import java.io.OutputStream
import java.io.InputStream
import com.dhpcs.jsonrpc._
import langserver.messages.ServerCommand
import scala.util.Try
import play.api.libs.json._
import scala.util.Failure
import scala.util.Success

class ConnectionImpl(inStream: InputStream, outStream: OutputStream) {
  val msgReader = new MessageReader(inStream)

  //  val msgWriter = 

  def start() {
    while (true) {
      val json = msgReader.nextPayload()

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

                jsonRpcRequestMessage.id -> Left(
                  JsonRpcResponseError.methodNotFound(jsonRpcRequestMessage.method)))(commandJsResult => commandJsResult.fold(

                  errors => jsonRpcRequestMessage.id -> Left(
                    JsonRpcResponseError.invalidParams(errors)),

                  command => jsonRpcRequestMessage.id -> Right(
                    command))))

    }
}