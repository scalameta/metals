package scala.meta.languageserver.providers

import java.nio.file.Files
import scala.meta.languageserver.Configuration
import scala.meta.languageserver.Configuration.Scalafmt
import scala.meta.languageserver.Formatter
import scala.meta.languageserver.MonixEnrichments._
import scala.meta.languageserver.protocol.Response
import scala.util.control.NonFatal
import com.typesafe.scalalogging.LazyLogging
import cats.syntax.bifunctor._
import cats.instances.either._
import langserver.core.Notifications
import langserver.types.MessageType
import langserver.types.Position
import langserver.types.Range
import langserver.types.TextEdit
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable
import org.langmeta.inputs.Input
import org.langmeta.io.AbsolutePath

class DocumentFormattingProvider(
    configuration: Observable[Configuration],
    cwd: AbsolutePath,
    notifications: Notifications
)(implicit s: Scheduler)
    extends LazyLogging {

  private val formatter: () => Either[String, Formatter] =
    configuration
      .focus(_.scalafmt.version)
      .map[Either[String, Formatter]] { version =>
        try {
          // TODO(olafur) convert Jars.fetch to use monix.Task to avoid blocking
          Right(Formatter.classloadScalafmt(version))
        } catch {
          case NonFatal(e) =>
            val msg =
              s"Unable to install scalafmt version $version, cause: ${e.getMessage}"
            Left(msg)
        }
      }
      .toFunction0()

  private val config: () => Either[String, Option[AbsolutePath]] =
    configuration
      .focus(_.scalafmt.confPath)
      .map[Either[String, Option[AbsolutePath]]] {
        case None =>
          val default = cwd.resolve(Scalafmt.defaultConfPath)
          if (Files.isRegularFile(default.toNIO)) Right(Some(default))
          else Right(None)
        case Some(relpath) =>
          val custom = cwd.resolve(relpath)
          if (Files.isRegularFile(custom.toNIO)) Right(Some(custom))
          else {
            Left(s"scalameta.scalafmt.confPath=$relpath is not a file")
          }
      }
      .toFunction0()

  private val fullDocumentRange = Range(
    start = Position(0, 0),
    end = Position(Int.MaxValue, Int.MaxValue)
  )

  def format(
      input: Input.VirtualFile
  ): Task[Either[Response.Error, List[TextEdit]]] = Task {
    val formatResult = for {
      scalafmt <- formatter()
      scalafmtConf <- config()
    } yield {
      scalafmtConf match {
        case None => // default config
          scalafmt.format(input.value, input.path)
        case Some(path) =>
          scalafmt.format(input.value, input.path, path)
      }
    }
    formatResult.bimap(
      message => {
        // We show a message here to be sure the message is
        // reported in the UI. invalidParams responses don't
        // get reported in vscode at least.
        notifications.showMessage(MessageType.Error, message)
        Response.invalidParams(message)
      },
      formatted => List(TextEdit(fullDocumentRange, formatted))
    )
  }

}
