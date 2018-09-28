package scala.meta.metals.providers

import java.nio.file.Files
import scala.meta.metals.Configuration
import scala.meta.metals.Configuration.Scalafmt
import scala.meta.metals.Formatter
import scala.meta.jsonrpc.MonixEnrichments._
import scala.meta.lsp.Position
import scala.meta.jsonrpc.JsonRpcClient
import scala.meta.jsonrpc.Response
import scala.meta.lsp.Window.showMessage
import scala.meta.RelativePath
import scala.util.control.NonFatal
import cats.syntax.bifunctor._
import cats.instances.either._
import scala.meta.lsp.Range
import scala.meta.lsp.TextEdit
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable
import org.langmeta.inputs.Input
import org.langmeta.io.AbsolutePath

class DocumentFormattingProvider(
    configuration: Observable[Configuration],
    cwd: AbsolutePath
)(implicit client: JsonRpcClient, s: Scheduler) {

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
          val default = cwd.resolve(RelativePath(Scalafmt.defaultConfPath))
          if (Files.isRegularFile(default.toNIO)) Right(Some(default))
          else Right(None)
        case Some(relpath) =>
          val custom = cwd.resolve(RelativePath(relpath))
          if (Files.isRegularFile(custom.toNIO))
            Right(Some(custom))
          else if (relpath == Configuration.Scalafmt.defaultConfPath)
            Right(None)
          else
            Left(s"metals.scalafmt.confPath=$relpath is not a file")
      }
      .toFunction0()

  def format(
      input: Input.VirtualFile
  ): Task[Either[Response.Error, List[TextEdit]]] = Task {
    val pos = scala.meta.Position.Range(input, 0, input.chars.length)
    val fullDocumentRange = Range(
      Position(pos.startLine, pos.startColumn),
      Position(pos.endLine, pos.endColumn)
    )
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
