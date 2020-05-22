package scala.meta.internal.metals

import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise

import scala.meta._
import scala.meta.internal.metals.Messages.MissingScalafmtConf
import scala.meta.internal.metals.Messages.MissingScalafmtVersion
import scala.meta.internal.metals.MetalsEnrichments._

import org.eclipse.lsp4j.jsonrpc.CancelChecker
import org.eclipse.{lsp4j => l}
import org.scalafmt.interfaces.PositionException
import org.scalafmt.interfaces.Scalafmt
import org.scalafmt.interfaces.ScalafmtReporter

/**
 * Implement text formatting using Scalafmt
 */
final class FormattingProvider(
    workspace: AbsolutePath,
    buffers: Buffers,
    userConfig: () => UserConfiguration,
    client: MetalsLanguageClient,
    clientConfig: ClientConfiguration,
    statusBar: StatusBar,
    icons: Icons,
    workspaceFolders: List[AbsolutePath],
    tables: Tables
)(implicit ec: ExecutionContext)
    extends Cancelable {
  override def cancel(): Unit = {
    scalafmt.clear()
  }

  private def clearDiagnostics(config: AbsolutePath): Unit = {
    client.publishDiagnostics(
      new l.PublishDiagnosticsParams(
        config.toURI.toString,
        Collections.emptyList()
      )
    )
  }

  private var scalafmt = FormattingProvider.newScalafmt()
  private val reporterPromise =
    new AtomicReference[Option[Promise[Boolean]]](None)
  private val cancelToken = new AtomicReference[Option[CancelChecker]](None)
  private def isCancelled: Boolean = cancelToken.get().exists(_.isCancelled)
  private def reset(token: CancelChecker): Unit = {
    reporterPromise.get().foreach(_.trySuccess(false))
    reporterPromise.set(None)
    cancelToken.set(Some(token))
  }

  // Warms up the Scalafmt instance so that the first formatting request responds faster.
  // Does nothing if there is no .scalafmt.conf or there is no configured version setting.
  def load(): Unit = {
    if (scalafmtConf.isFile && !Testing.isEnabled) {
      scalafmt.format(
        scalafmtConf.toNIO,
        Paths.get("Main.scala"),
        "object Main  {}"
      )
    }
  }

  def format(
      path: AbsolutePath,
      token: CancelChecker
  ): Future[util.List[l.TextEdit]] = {
    scalafmt = scalafmt.withReporter(activeReporter)
    reset(token)
    val input = path.toInputFromBuffers(buffers)
    if (!scalafmtConf.isFile) {
      handleMissingFile(scalafmtConf).map {
        case true =>
          runFormat(path, input).asJava
        case false =>
          Collections.emptyList[l.TextEdit]()
      }
    } else {
      val result = runFormat(path, input)
      if (token.isCancelled) {
        statusBar.addMessage(
          s"${icons.info}Scalafmt cancelled by editor, try saving file again"
        )
      }
      reporterPromise.get() match {
        case Some(promise) =>
          // Wait until "update .scalafmt.conf" dialogue has completed
          // before returning future.
          promise.future.map {
            case true if !token.isCancelled => runFormat(path, input).asJava
            case _ => result.asJava
          }
        case None =>
          Future.successful(result.asJava)
      }
    }
  }

  private def runFormat(path: AbsolutePath, input: Input): List[l.TextEdit] = {
    val fullDocumentRange = Position.Range(input, 0, input.chars.length).toLSP
    val formatted = scalafmt.format(scalafmtConf.toNIO, path.toNIO, input.text)
    if (formatted != input.text) {
      List(new l.TextEdit(fullDocumentRange, formatted))
    } else {
      Nil
    }
  }

  private def handleMissingVersion(config: AbsolutePath): Future[Boolean] = {
    askScalafmtVersion().map {
      case Some(version) =>
        val text = config.toInputFromBuffers(buffers).text
        val newText =
          s"""version = "$version"
             |""".stripMargin + text
        Files.write(config.toNIO, newText.getBytes(StandardCharsets.UTF_8))
        clearDiagnostics(config)
        client.showMessage(
          MissingScalafmtVersion.fixedVersion(isCancelled)
        )
        true
      case None =>
        scribe.info("scalafmt: no version provided for .scalafmt.conf")
        false
    }
  }

  private def askScalafmtVersion(): Future[Option[String]] = {
    if (!tables.dismissedNotifications.ChangeScalafmtVersion.isDismissed) {
      if (clientConfig.isInputBoxEnabled) {
        client
          .metalsInputBox(MissingScalafmtVersion.inputBox())
          .asScala
          .map(response => Option(response.value))
      } else {
        client
          .showMessageRequest(MissingScalafmtVersion.messageRequest())
          .asScala
          .map { item =>
            if (item == MissingScalafmtVersion.changeVersion) {
              Some(BuildInfo.scalafmtVersion)
            } else if (item == Messages.notNow) {
              tables.dismissedNotifications.ChangeScalafmtVersion
                .dismiss(24, TimeUnit.HOURS)
              None
            } else if (item == Messages.dontShowAgain) {
              tables.dismissedNotifications.ChangeScalafmtVersion
                .dismissForever()
              None
            } else None
          }
      }
    } else Future.successful(None)
  }

  private def handleMissingFile(path: AbsolutePath): Future[Boolean] = {
    if (!tables.dismissedNotifications.CreateScalafmtFile.isDismissed) {
      val params = MissingScalafmtConf.params(path)
      client.showMessageRequest(params).asScala.map { item =>
        if (item == MissingScalafmtConf.createFile) {
          val text =
            s"""version = "${BuildInfo.scalafmtVersion}"
               |""".stripMargin
          Files.createDirectories(path.toNIO.getParent)
          Files.write(path.toNIO, text.getBytes(StandardCharsets.UTF_8))
          client.showMessage(MissingScalafmtConf.fixedParams(isCancelled))
          true
        } else if (item == Messages.notNow) {
          tables.dismissedNotifications.CreateScalafmtFile
            .dismiss(24, TimeUnit.HOURS)
          false
        } else if (item == Messages.dontShowAgain) {
          tables.dismissedNotifications.CreateScalafmtFile
            .dismissForever()
          false
        } else false
      }
    } else Future.successful(false)
  }

  private def scalafmtConf: AbsolutePath = {
    val configpath = userConfig().scalafmtConfigPath
    (workspace :: workspaceFolders).iterator
      .map(_.resolve(configpath))
      .collectFirst { case path if path.isFile => path }
      .getOrElse(workspace.resolve(configpath))
  }

  private val activeReporter: ScalafmtReporter = new ScalafmtReporter {
    private var downloadingScalafmt = Promise[Unit]()
    override def error(file: Path, message: String): Unit = {
      scribe.error(s"scalafmt: $file: $message")
      if (file == scalafmtConf.toNIO) {
        downloadingScalafmt.trySuccess(())
        if (message.contains("failed to resolve Scalafmt version")) {
          client.showMessage(MissingScalafmtVersion.failedToResolve(message))
        }
        val input = scalafmtConf.toInputFromBuffers(buffers)
        val pos = Position.Range(input, 0, input.chars.length)
        client.publishDiagnostics(
          new l.PublishDiagnosticsParams(
            file.toUri.toString,
            Collections.singletonList(
              new l.Diagnostic(
                new l.Range(
                  new l.Position(0, 0),
                  new l.Position(pos.endLine, pos.endColumn)
                ),
                message,
                l.DiagnosticSeverity.Error,
                "scalafmt"
              )
            )
          )
        )
      }
    }

    override def error(file: Path, e: Throwable): Unit = {
      downloadingScalafmt.trySuccess(())
      e match {
        case p: PositionException =>
          statusBar.addMessage(
            s"${icons.alert}line ${p.startLine() + 1}: ${p.shortMessage()}"
          )
          scribe.error(s"scalafmt: ${p.longMessage()}")
        case _ =>
          scribe.error(s"scalafmt: $file", e)
      }
    }
    override def excluded(file: Path): Unit = {
      scribe.info(
        s"scalafmt: excluded $file (to format this file, update `project.excludeFilters` in .scalafmt.conf)"
      )
    }

    override def parsedConfig(config: Path, scalafmtVersion: String): Unit = {
      downloadingScalafmt.trySuccess(())
      clearDiagnostics(AbsolutePath(config))
    }

    override def missingVersion(config: Path, defaultVersion: String): Unit = {
      val promise = Promise[Boolean]()
      reporterPromise.set(Some(promise))
      promise.completeWith(handleMissingVersion(AbsolutePath(config)))
      super.missingVersion(config, defaultVersion)
    }

    def downloadOutputStreamWriter(): OutputStreamWriter =
      new OutputStreamWriter(downloadOutputStream())

    def downloadOutputStream(): OutputStream = {
      downloadingScalafmt.trySuccess(())
      downloadingScalafmt = Promise()
      statusBar.trackSlowFuture(
        "Loading Scalafmt",
        downloadingScalafmt.future
      )
      System.out
    }
    override def downloadWriter(): PrintWriter = {
      new PrintWriter(downloadOutputStream())
    }
  }
}

object FormattingProvider {
  def newScalafmt(): Scalafmt =
    Scalafmt
      .create(this.getClass.getClassLoader)
      .withReporter(EmptyScalafmtReporter)
      .withDefaultVersion(BuildInfo.scalafmtVersion)
}
