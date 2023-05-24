package scala.meta.internal.metals

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.control.NonFatal

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.io.AbsolutePath

import org.eclipse.{lsp4j => l}

class BSPErrorHandler(
    languageClient: MetalsLanguageClient,
    workspaceFolder: AbsolutePath,
)(implicit context: ExecutionContext) {

  private def logsPath = workspaceFolder.resolve(Directories.log)
  private val currentError = new AtomicReference[String]("")

  def onError(message: String): Unit = {
    currentError.getAndUpdate {
      case "" =>
        showErrorWithDelay()
        message
      case collectedError => collectedError + s"\n$message"
    }
  }

  private def showErrorWithDelay() = {
    Future {
      Thread.sleep(1000) // 1 sec
      currentError.getAndSet("")
    }.flatMap(showError)
  }

  private def showError(message: String): Future[Unit] = {
    scribe.error(s"${BSPErrorHandler.errorInBsp}\n$message")
    BSPErrorHandler.makeShowMessage(message) match {
      case Right(params) =>
        Future.successful(languageClient.showMessage(params))
      case Left(params) =>
        languageClient.showMessageRequest(params).asScala.map {
          case msg if msg == BSPErrorHandler.goToLogs =>
            val errorMsgStartLine =
              message.linesIterator.headOption
                .flatMap(findLine(_))
                .getOrElse(0)
            gotoLogs(errorMsgStartLine)
          case _ => Future.successful(())
        }
    }
  }

  private def findLine(line: String): Option[Int] =
    try {
      val (_, lineNumber) =
        Files
          .newBufferedReader(logsPath.toNIO)
          .lines()
          .asScala
          .zipWithIndex
          .fold(("", 0)) {
            case ((lineBefore, matchingLine), (currentLine, currentIndex)) =>
              if (
                lineBefore.contains(BSPErrorHandler.errorInBsp) &&
                currentLine.contains(line)
              ) (currentLine, currentIndex - 1)
              else (currentLine, matchingLine)
          }
      Some(lineNumber)
    } catch {
      case NonFatal(_) => None
    }

  private def gotoLogs(line: Int) = {
    val pos = new l.Position(line, 0)
    val location = new l.Location(
      logsPath.toURI.toString(),
      new l.Range(pos, pos),
    )
    languageClient.metalsExecuteClientCommand(
      ClientCommands.GotoLocation.toExecuteCommandParams(
        ClientCommands.WindowLocation(
          location.getUri(),
          location.getRange(),
        )
      )
    )
  }
}

object BSPErrorHandler {
  def makeShowMessage(
      message: String
  ): Either[l.ShowMessageRequestParams, l.MessageParams] = {
    if (message.length() <= MESSAGE_MAX_LENGTH) {
      val messageParams = new l.MessageParams(
        l.MessageType.Error,
        s"""|$errorHeader:
            |$message""".stripMargin,
      )
      Right(messageParams)
    } else {
      val params = new l.ShowMessageRequestParams()
      params.setMessage(
        s"""|$errorHeader
            |${message.take(MESSAGE_MAX_LENGTH)}...
            |$gotoLogsToSeeFull""".stripMargin
      )
      params.setType(l.MessageType.Error)
      params.setActions(List(goToLogs).asJava)
      Left(params)
    }
  }

  private val errorHeader = "Encountered an error in the build server:"
  private val goToLogs = new l.MessageActionItem("Go to logs.")
  private val gotoLogsToSeeFull = "Go to logs to see the full error"
  private val errorInBsp = "Error in BSP:"

  private val MESSAGE_MAX_LENGTH = 150

}
