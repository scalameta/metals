package scala.meta.internal.metals

import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util
import java.util.Collections
import org.eclipse.lsp4j.jsonrpc.CancelChecker
import org.eclipse.{lsp4j => l}
import org.scalafmt.interfaces.PositionException
import org.scalafmt.interfaces.Scalafmt
import org.scalafmt.interfaces.ScalafmtReporter
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.meta._
import scala.meta.internal.metals.Messages.MissingScalafmtConf
import scala.meta.internal.metals.Messages.MissingScalafmtVersion
import scala.meta.internal.metals.MetalsEnrichments._

/**
 * Implement text formatting using Scalafmt
 */
final class FormattingProvider(
    workspace: AbsolutePath,
    buffers: Buffers,
    embedded: Embedded,
    serverConfig: MetalsServerConfig,
    userConfig: () => UserConfiguration,
    client: MetalsLanguageClient,
    statusBar: StatusBar,
    icons: Icons
)(implicit ec: ExecutionContext)
    extends Cancelable {
  override def cancel(): Unit = {
    scalafmt.clear()
  }

  private def scalafmtConf: AbsolutePath =
    workspace.resolve(userConfig().scalafmtConfigPath)
  private val reporter: ScalafmtReporter = new ScalafmtReporter {
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
      client.publishDiagnostics(
        new l.PublishDiagnosticsParams(
          config.toUri.toString,
          Collections.emptyList()
        )
      )
    }

    override def missingVersion(config: Path, defaultVersion: String): Unit = {
      handleMissingVersion(AbsolutePath(config))
      super.missingVersion(config, defaultVersion)
    }

    override def downloadWriter(): PrintWriter = {
      downloadingScalafmt.trySuccess(())
      downloadingScalafmt = Promise()
      statusBar.trackFuture(
        s"${icons.sync}Loading Scalafmt",
        downloadingScalafmt.future
      )
      new PrintWriter(System.out)
    }
  }

  private val scalafmt = Scalafmt
    .create(this.getClass.getClassLoader)
    .withReporter(reporter)

  def format(
      path: AbsolutePath,
      token: CancelChecker
  ): util.List[l.TextEdit] = {
    val input = path.toInputFromBuffers(buffers)
    val fullDocumentRange = Position.Range(input, 0, input.chars.length).toLSP
    if (!scalafmtConf.isFile) {
      handleMissingFile(scalafmtConf)
      Collections.emptyList()
    } else {
      val formatted =
        scalafmt.format(scalafmtConf.toNIO, path.toNIO, input.text)
      if (token.isCancelled) {
        statusBar.addMessage(
          s"${icons.info}Scalafmt cancelled by editor, try saving file again"
        )
      }
      if (formatted != input.text) {
        List(new l.TextEdit(fullDocumentRange, formatted)).asJava
      } else {
        Collections.emptyList()
      }
    }
  }

  def handleMissingVersion(path: AbsolutePath): Unit = {
    askScalafmtVersion().foreach {
      case Some(version) =>
        val text = path.toInputFromBuffers(buffers).text
        val newText =
          s"""version = "$version"
             |""".stripMargin + text
        Files.write(path.toNIO, newText.getBytes(StandardCharsets.UTF_8))
        client.showMessage(MissingScalafmtVersion.fixedVersion)
      case None =>
        scribe.info("scalafmt: no version provided for .scalafmt.conf")
    }
  }

  def askScalafmtVersion(): Future[Option[String]] = {
    if (serverConfig.isInputBoxEnabled) {
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
          } else {
            None
          }
        }
    }
  }

  def handleMissingFile(path: AbsolutePath): Unit = {
    val params = MissingScalafmtConf.params(path)
    client.showMessageRequest(params).asScala.foreach { item =>
      if (item == MissingScalafmtConf.createFile) {
        val text =
          s"""version = "${BuildInfo.scalafmtVersion}"
             |""".stripMargin
        Files.createDirectories(path.toNIO.getParent)
        Files.write(path.toNIO, text.getBytes(StandardCharsets.UTF_8))
        client.showMessage(MissingScalafmtConf.fixedParams)
      }
    }
  }
}
