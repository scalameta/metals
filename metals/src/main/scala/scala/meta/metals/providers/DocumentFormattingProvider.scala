package scala.meta.metals.providers

import java.nio.file.Files
import scala.meta.metals.Configuration
import scala.meta.metals.Configuration.Scalafmt
import scala.meta.metals.Formatter
import org.langmeta.lsp.MonixEnrichments._
import org.langmeta.lsp.Position
import org.langmeta.lsp.TextEdit
import io.github.lsp4s.jsonrpc.JsonRpcClient
import io.github.lsp4s.jsonrpc.Response
import org.langmeta.lsp.Window.showMessage
import scala.util.control.NonFatal
import com.typesafe.scalalogging.LazyLogging
import cats.syntax.bifunctor._
import cats.instances.either._
import org.langmeta.lsp.Range
import org.langmeta.lsp.TextEdit
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable
import org.langmeta.inputs.Input
import org.langmeta.io.AbsolutePath

class DocumentFormattingProvider(
    configuration: Observable[Configuration],
    cwd: AbsolutePath
)(implicit client: JsonRpcClient, s: Scheduler)
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
          if (Files.isRegularFile(custom.toNIO))
            Right(Some(custom))
          else if (relpath == Configuration.Scalafmt.defaultConfPath)
            Right(None)
          else
            Left(s"metals.scalafmt.confPath=$relpath is not a file")
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
        showMessage.error(message)
        Response.invalidParams(message)
      },
      formatted => List(TextEdit(fullDocumentRange, formatted))
    )
  }

}
