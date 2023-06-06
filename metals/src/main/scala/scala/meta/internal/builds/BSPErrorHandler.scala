package scala.meta.internal.builds

import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.control.NonFatal

import scala.meta.internal.metals.ClientCommands
import scala.meta.internal.metals.Directories
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.io.AbsolutePath

import org.eclipse.{lsp4j => l}

class BSPErrorHandler(
    languageClient: MetalsLanguageClient,
    workspaceFolder: AbsolutePath,
)(implicit context: ExecutionContext) {
  val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(1)

  private def logsPath = workspaceFolder.resolve(Directories.log)
  private val currentError =
    new AtomicReference[CurrentAndPreviousError](new CurrentAndPreviousError())

  def onError(message: String): Unit = {
    val collectedError = currentError.getAndUpdate(_.append(message))
    if (collectedError.isEmpty) {
      showErrorWithDelay()
    }
  }

  private def showErrorWithDelay() = {
    val runnable = new Runnable {
      def run(): Unit = {
        val error = currentError.getAndUpdate(_.newError())
        error.error match {
          case Some(message) => showError(filterANSIColorCodes(message))
          case None => Future.unit
        }
      }
    }
    scheduler.schedule(runnable, 1, TimeUnit.SECONDS)
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
              ) (currentLine, currentIndex)
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
  private val errorInBsp = "Build server error:"

  private val MESSAGE_MAX_LENGTH = 150

}

class CurrentAndPreviousError(
    previousError: Option[String] = None,
    currentError: String = "",
) {
  lazy val error: Option[String] = {
    if (currentError.isEmpty()) None
    else {
      val deduplicatedError = deduplicateErrorMessage(currentError)
      if (previousError.contains(deduplicatedError)) None
      else Some(deduplicatedError)
    }
  }
  def append(message: String): CurrentAndPreviousError = {
    val newCurrent =
      if (currentError.isEmpty()) message else currentError + s"\n$message"
    new CurrentAndPreviousError(previousError, newCurrent)
  }
  def isEmpty: Boolean = currentError.isEmpty()
  def newError(): CurrentAndPreviousError = {
    error match {
      case None => new CurrentAndPreviousError(previousError)
      case someError => new CurrentAndPreviousError(someError)
    }
  }

  /**
   * Sometimes the same error gets logged a few times and the error msg is a repetition of the same string.
   * This is a heuristic for deduplicating is such situations.
   * We use the first line of the error to split it into chunks that potentially repetitions of the same message and perform distinct on those chunks.
   */
  private def deduplicateErrorMessage(currentError: String): String = {
    def splitAtLinesContaining(
        elem: String,
        lines: List[String],
    ): List[List[String]] = {
      @tailrec
      def go(
          lines: List[String],
          collectedSplits: List[List[String]],
      ): List[List[String]] =
        lines match {
          case Nil => collectedSplits
          case `elem` :: tail => go(tail, Nil :: collectedSplits)
          case headLine :: tail =>
            collectedSplits match {
              case head :: rest => go(tail, (headLine :: head) :: rest)
              case Nil => List(headLine) :: Nil
            }
        }
      go(lines, Nil).map(_.reverse).reverse
    }
    currentError.linesIterator.headOption
      .map { firstLine =>
        splitAtLinesContaining(
          firstLine,
          currentError.linesIterator.toList,
        ).distinct.flatten.mkString("\n")
      }
      .getOrElse(currentError)
  }
}
