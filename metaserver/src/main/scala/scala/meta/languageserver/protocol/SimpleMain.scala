package scala.meta.languageserver.protocol

import java.io.FileOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.util.concurrent.Executors
import scala.util.Properties
import scala.util.control.NonFatal
import com.typesafe.scalalogging.LazyLogging
import langserver.messages.CompletionList
import langserver.messages.CompletionOptions
import langserver.messages.DidChangeTextDocumentParams
import langserver.messages.DidCloseTextDocumentParams
import langserver.messages.DidOpenTextDocumentParams
import langserver.messages.DidSaveTextDocumentParams
import langserver.messages.InitializeParams
import langserver.messages.InitializeResult
import langserver.messages.ServerCapabilities
import langserver.messages.TextDocumentPositionParams
import langserver.types.CompletionItem
import langserver.types.TextDocumentSyncKind
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.schedulers.SchedulerService
import org.langmeta.internal.io.PathIO
import play.api.libs.json.JsNull
import play.api.libs.json.JsValue
import play.api.libs.json.Reads
import play.api.libs.json.Writes

trait Router {
  def requestAsync[A: Reads, B: Writes](method: String)(
      f: A => Task[Either[Response.Error, B]]
  ): Router
  def request[A: Reads, B: Writes](method: String)(
      f: A => Either[Response.Error, B]
  ): Router =
    requestAsync[A, B](method)(request => Task(f(request)))
  def notificationAsync[A: Reads](method: String)(f: A => Task[Unit]): Router
  def notification[A: Reads](method: String)(f: A => Unit): Router =
    notificationAsync[A](method)(request => Task(f(request)))
}

object SimpleMain extends LazyLogging {

  def main(args: Array[String]): Unit = {
    val cwd = PathIO.workingDirectory
    val configDir = cwd.resolve(".metaserver").toNIO
    val logFile = configDir.resolve("metaserver.log").toFile
    Files.createDirectories(configDir)
    val out = new PrintStream(new FileOutputStream(logFile))
    val err = new PrintStream(new FileOutputStream(logFile))
    val stdin = System.in
    val stdout = System.out
    val stderr = System.err
    val s: SchedulerService =
      Scheduler(Executors.newFixedThreadPool(4))
    try {
      // route System.out somewhere else. Any output not from the server (e.g. logging)
      // messes up with the client, since stdout is used for the language server protocol
      System.setOut(out)
      System.setErr(err)
      logger.info(s"Starting server in $cwd")
      logger.info(s"Classpath: ${Properties.javaClassPath}")
      val services = Services.init
        .request[InitializeParams, InitializeResult]("initialize") { params =>
          pprint.log(params)
          Right(
            InitializeResult(
              ServerCapabilities(
                completionProvider =
                  Some(CompletionOptions(resolveProvider = true, "." :: Nil))
              )
            )
          )
        }
        .request[JsValue, JsValue]("shutdown") { _ =>
          pprint.log("shutdown")
          Right(JsNull)
        }
        .notification[JsValue]("exit") { _ =>
          pprint.log("exit")
          sys.exit(0)
        }
        .request[TextDocumentPositionParams, List[CompletionItem]](
          "textDocument/completion"
        ) { params =>
          pprint.log(params)
          Right(Nil)
        }
        .notification[DidCloseTextDocumentParams](
          "textDocument/didClose"
        ) { params =>
          pprint.log(params)
          ()
        }
        .notification[DidOpenTextDocumentParams](
          "textDocument/didOpen"
        ) { params =>
          pprint.log(params)
          ()
        }
        .notification[DidChangeTextDocumentParams](
          "textDocument/didChange"
        ) { params =>
          pprint.log(params)
          ()
        }
        .notification[DidSaveTextDocumentParams](
          "textDocument/didSave"
        ) { params =>
          pprint.log(params)
          ()
        }
      val server = new LanguageServer(
        BaseProtocolMessage.fromInputStream(stdin),
        stdout,
        services.asInstanceOf[Services],
        s
      )
      server.listen()
      logger.warn("Stopped listening :(")
    } catch {
      case NonFatal(e) =>
        logger.error("Uncaught top-level error", e)
    } finally {
      System.setOut(stdout)
      System.setErr(stderr)
    }
    System.exit(0)
  }

}
