package scala.meta.internal.builds

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.control.NonFatal

import scala.meta.internal.bsp.BspSession
import scala.meta.internal.metals.ClientCommands
import scala.meta.internal.metals.ConcurrentHashSet
import scala.meta.internal.metals.Directories
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.Tables
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.io.AbsolutePath

import org.eclipse.{lsp4j => l}

class BspErrorHandler(
    languageClient: MetalsLanguageClient,
    workspaceFolder: AbsolutePath,
    currentSession: () => Option[BspSession],
    tables: Tables,
)(implicit context: ExecutionContext) {

  protected def logsPath: AbsolutePath =
    workspaceFolder.resolve(Directories.log)
  private val lastError = new AtomicReference[String]("")
  private val dismissedErrors = ConcurrentHashSet.empty[String]

  def onError(message: String): Future[Unit] = {
    if (
      !tables.dismissedNotifications.BspErrors.isDismissed &&
      shouldShowBspError &&
      !dismissedErrors.contains(message)
    ) {
      val previousError = lastError.getAndSet(message)
      if (message != previousError) {
        showError(message)
      } else Future.successful(())
    } else {
      logError(message)
      Future.successful(())
    }
  }

  def shouldShowBspError: Boolean = currentSession().exists(session =>
    session.main.isBloop || session.main.isScalaCLI
  )

  protected def logError(message: String): Unit = scribe.error(message)

  private def showError(message: String): Future[Unit] = {
    val bspError = s"${BspErrorHandler.errorInBsp} $message"
    logError(bspError)
    val params = BspErrorHandler.makeShowMessage(message)
    languageClient.showMessageRequest(params).asScala.flatMap {
      case BspErrorHandler.goToLogs =>
        val errorMsgStartLine =
          bspError.linesIterator.headOption
            .flatMap(findLine(_))
            .getOrElse(0)
        Future.successful(gotoLogs(errorMsgStartLine))
      case BspErrorHandler.dismiss =>
        Future.successful(dismissedErrors.add(message)).ignoreValue
      case BspErrorHandler.doNotShowErrors =>
        Future.successful {
          tables.dismissedNotifications.BspErrors.dismissForever
        }.ignoreValue
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
          makeShortMessage(message),
          List(dismiss, doNotShowErrors),
        )
      } else {
        (
          makeLongMessage(message),
          List(goToLogs, dismiss, doNotShowErrors),
        )
      }
    val params = new l.ShowMessageRequestParams()
    params.setType(l.MessageType.Error)
    params.setMessage(msg)
    params.setActions(actions.asJava)
    params
  }

  def makeShortMessage(message: String): String =
    s"""|$errorHeader
        |$message""".stripMargin

  def makeLongMessage(message: String): String =
    s"""|${makeShortMessage(s"${message.take(MESSAGE_MAX_LENGTH)}...")}
        |$gotoLogsToSeeFull""".stripMargin

  val goToLogs = new l.MessageActionItem("Go to logs.")
  val dismiss = new l.MessageActionItem("Dismiss.")
  val doNotShowErrors = new l.MessageActionItem("Stop showing bsp errors.")

  val errorHeader = "Encountered an error in the build server:"
  private val gotoLogsToSeeFull = "Go to logs to see the full error"

  val errorInBsp = "Build server error:"

  private val MESSAGE_MAX_LENGTH = 150

}
