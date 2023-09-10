package scala.meta.internal.builds

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.control.NonFatal

import scala.meta.internal.bsp.BspSession
import scala.meta.internal.metals.ClientCommands
import scala.meta.internal.metals.Directories
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.io.AbsolutePath

import org.eclipse.{lsp4j => l}

class BspErrorHandler(
    languageClient: MetalsLanguageClient,
    workspaceFolder: AbsolutePath,
    restartBspServer: () => Future[Boolean],
    currentSession: () => Option[BspSession],
)(implicit context: ExecutionContext) {

  private def logsPath = workspaceFolder.resolve(Directories.log)
  private val lastError = new AtomicReference[String]("")

  def onError(message: String): Unit = {
    if (shouldShowBspError) {
      val previousError = lastError.getAndSet(message)
      if (message != previousError) {
        showError(message)
      }
    } else {
      scribe.error(message)
    }
  }

  private def shouldShowBspError = currentSession().exists(session =>
    session.main.isBloop || session.main.isScalaCLI
  )

  private def showError(message: String): Future[Unit] = {
    val bspError = s"${BspErrorHandler.errorInBsp}: $message"
    scribe.error(bspError)
    val params = BspErrorHandler.makeShowMessage(message)
    languageClient.showMessageRequest(params).asScala.flatMap {
      case BspErrorHandler.goToLogs =>
        val errorMsgStartLine =
          bspError.linesIterator.headOption
            .flatMap(findLine(_))
            .getOrElse(0)
        Future.successful(gotoLogs(errorMsgStartLine))
      case BspErrorHandler.restartBuildServer =>
        restartBspServer().ignoreValue
      case _ => Future.successful(())
    }
  }

  private def findLine(line: String): Option[Int] =
    try {
      val lineNumber =
        Files
          .readAllLines(logsPath.toNIO)
          .asScala
          .lastIndexWhere(_.contains(line))
      if (lineNumber >= 0) Some(lineNumber) else None
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

object BspErrorHandler {
  def makeShowMessage(
      message: String
  ): l.ShowMessageRequestParams = {
    val (msg, actions) =
      if (message.length() <= MESSAGE_MAX_LENGTH) {
        (
          s"""|$errorHeader
              |$message""".stripMargin,
          List(restartBuildServer, dismiss),
        )
      } else {
        (
          s"""|$errorHeader
              |${message.take(MESSAGE_MAX_LENGTH)}...
              |$gotoLogsToSeeFull""".stripMargin,
          List(goToLogs, restartBuildServer, dismiss),
        )
      }
    val params = new l.ShowMessageRequestParams()
    params.setType(l.MessageType.Error)
    params.setMessage(msg)
    params.setActions(actions.asJava)
    params
  }

  private val errorHeader = "Encountered an error in the build server:"
  private val goToLogs = new l.MessageActionItem("Go to logs.")
  private val restartBuildServer =
    new l.MessageActionItem("Restart build server.")
  private val dismiss = new l.MessageActionItem("Dismiss.")
  private val gotoLogsToSeeFull = "Go to logs to see the full error"
  private val errorInBsp = "Build server error:"

  private val MESSAGE_MAX_LENGTH = 150

}
