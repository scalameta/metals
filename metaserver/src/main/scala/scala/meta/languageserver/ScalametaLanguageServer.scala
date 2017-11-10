package scala.meta.languageserver

import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import scala.collection.mutable.ListBuffer
import scala.meta.languageserver.ScalametaEnrichments._
import scala.util.control.NonFatal
import langserver.core.LanguageServer
import langserver.messages.ClientCapabilities
import langserver.messages.CompletionList
import langserver.messages.CompletionOptions
import langserver.messages.DefinitionResult
import langserver.messages.FileChangeType
import langserver.messages.FileEvent
import langserver.messages.MessageType
import langserver.messages.ResultResponse
import langserver.messages.ServerCapabilities
import langserver.messages.ShutdownResult
import langserver.messages.Hover
import langserver.types._
import monix.execution.Cancelable
import monix.execution.Scheduler
import monix.reactive.MulticastStrategy
import monix.reactive.Observable
import monix.reactive.Observer
import monix.reactive.OverflowStrategy
import org.langmeta.internal.semanticdb.schema
import org.langmeta.io.AbsolutePath
import org.langmeta.semanticdb.Database
import org.langmeta.semanticdb.Denotation

class ScalametaLanguageServer(
    cwd: AbsolutePath,
    lspIn: InputStream,
    lspOut: OutputStream,
    stdout: PrintStream
)(implicit s: Scheduler)
    extends LanguageServer(lspIn, lspOut) {
  implicit val workspacePath: AbsolutePath = cwd
  val (semanticdbSubscriber, semanticdbPublisher) =
    ScalametaLanguageServer.semanticdbStream
  val (compilerConfigSubscriber, compilerConfigPublisher) =
    Observable.multicast[AbsolutePath](
      MulticastStrategy.Publish,
      OverflowStrategy.ClearBuffer(2)
    )
  def onError(e: Throwable): Unit = {
    logger.error(e.getMessage, e)
  }
  val buffers: Buffers = Buffers()
  val symbol: SymbolIndexer =
    SymbolIndexer(semanticdbPublisher, logger, connection, buffers)
  val scalafix: Linter =
    new Linter(cwd, stdout, connection, semanticdbPublisher.doOnError(onError))
  val scalafmt: Formatter = Formatter.classloadScalafmt("1.3.0", stdout)
  val compiler = new Compiler(
    compilerConfigPublisher.doOnError(onError),
    connection,
    buffers
  )

  // TODO(olafur) more holistic error handling story.
  private def unsafe(thunk: => Unit): Unit =
    try thunk
    catch { case NonFatal(e) => logger.error(e.getMessage, e) }

  private def unsafeSeq[T](thunk: => Seq[T]): Seq[T] =
    try thunk
    catch { case NonFatal(e) => logger.error(e.getMessage, e); Nil }

  private val toCancel = ListBuffer.empty[Cancelable]

  private def loadAllSemanticdbsInWorkspace(): Unit = {
    Files.walkFileTree(
      workspacePath.toNIO,
      new SimpleFileVisitor[Path] {
        override def visitFile(
            file: Path,
            attrs: BasicFileAttributes
        ): FileVisitResult = {
          onChangedFile(AbsolutePath(file))(_ => ())
          FileVisitResult.CONTINUE
        }
      }
    )
  }

  override def initialize(
      pid: Long,
      rootPath: String,
      capabilities: ClientCapabilities
  ): ServerCapabilities = {
    logger.info(s"Initialized with $cwd, $pid, $rootPath, $capabilities")
    toCancel += scalafix.linter.subscribe()
    toCancel += symbol.indexer.subscribe()
    toCancel += compiler.onNewCompilerConfig.subscribe()
    loadAllSemanticdbsInWorkspace()
    ServerCapabilities(
      completionProvider = Some(
        CompletionOptions(
          resolveProvider = true,
          triggerCharacters = "." :: Nil
        )
      ),
      definitionProvider = true,
      documentSymbolProvider = true,
      documentFormattingProvider = true,
      hoverProvider = true
    )
  }

  override def shutdown(): Unit = {
    toCancel.foreach(_.cancel())
  }

  private def onChangedFile(
      path: AbsolutePath
  )(fallback: AbsolutePath => Unit): Unit = {
    logger.info(s"File $path changed.")
    val name = path.toNIO.getFileName.toString
    if (name.endsWith(".semanticdb")) {
      semanticdbSubscriber.onNext(path)
    } else if (name.endsWith(".compilerconfig")) {
      compilerConfigSubscriber.onNext(path)
    } else {
      fallback(path)
    }
  }

  override def onChangeWatchedFiles(changes: Seq[FileEvent]): Unit =
    changes.foreach {
      case FileEvent(
          Uri(path),
          FileChangeType.Created | FileChangeType.Changed
          ) =>
        onChangedFile(path) { _ =>
          logger.warn(s"Unknown file extension for path $path")
        }

      case event =>
        logger.info(s"Unhandled file event: $event")
        ()
    }

  override def documentFormattingRequest(
      td: TextDocumentIdentifier,
      options: FormattingOptions
  ): List[TextEdit] = {
    try {
      val path = Uri.toPath(td.uri).get
      val contents = buffers.read(path)
      val fullDocumentRange = Range(
        start = Position(0, 0),
        end = Position(Int.MaxValue, Int.MaxValue)
      )
      val config = cwd.resolve(".scalafmt.conf")
      if (Files.isRegularFile(config.toNIO)) {
        val formattedContent =
          scalafmt.format(contents, config.toString(), path.toString())
        List(TextEdit(fullDocumentRange, formattedContent))
      } else {
        connection.showMessage(MessageType.Info, s"Missing $config")
        Nil
      }
    } catch {
      case NonFatal(e) =>
        connection.showMessage(MessageType.Error, e.getMessage)
        logger.error(e.getMessage, e)
        Nil
    }
  }

  override def onOpenTextDocument(td: TextDocumentItem): Unit =
    Uri.toPath(td.uri).foreach(p => buffers.changed(p, td.text))

  override def onChangeTextDocument(
      td: VersionedTextDocumentIdentifier,
      changes: Seq[TextDocumentContentChangeEvent]
  ): Unit = {
    changes.foreach { c =>
      Uri.toPath(td.uri).foreach(p => buffers.changed(p, c.text))
    }
  }

  override def documentSymbols(
      td: TextDocumentIdentifier
  ): Seq[SymbolInformation] = {
    val path = Uri.toPath(td.uri).get
    symbol.documentSymbols(path.toRelative(cwd)).map {
      case (position, denotation @ Denotation(_, name, signature, _)) =>
        val location = path.toLocation(position)
        val kind = denotation.symbolKind
        SymbolInformation(name, kind, location, Some(signature))
    }
  }

  override def gotoDefinitionRequest(
      td: TextDocumentIdentifier,
      position: Position
  ): DefinitionResult = {
    val path = Uri.toPath(td.uri).get.toRelative(cwd)
    symbol
      .goToDefinition(path, position.line, position.character)
      .fold(DefinitionResult(Nil)) { position =>
        DefinitionResult(
          cwd.resolve(position.input.syntax).toLocation(position) :: Nil
        )
      }
  }

  override def onSaveTextDocument(td: TextDocumentIdentifier): Unit = {}

  override def completionRequest(
      td: TextDocumentIdentifier,
      position: Position
  ): ResultResponse = {
    try {
      val completions = compiler.autocomplete(
        Uri.toPath(td.uri).get,
        position.line,
        position.character
      )
      CompletionList(
        isIncomplete = false,
        items = completions.map {
          case (signature, name) =>
            CompletionItem(
              label = name,
              detail = Some(signature)
            )
        }
      )
    } catch {
      case NonFatal(e) =>
        onError(e)
        ShutdownResult(-1)
    }
  }

  override def hoverRequest(
      td: TextDocumentIdentifier,
      position: Position
  ): Hover = {
    val path = Uri.toPath(td.uri).get.toRelative(cwd)
    symbol.hoverInformation(path, position.line, position.character) match {
      case Some((pos, denotation)) if denotation.signature.nonEmpty =>
        Hover(
          contents = List(
            RawMarkedString(language = "scala", value = denotation.signature)
          ),
          range = Some(pos.toRange)
        )
      case _ => Hover(Nil, None)
    }
  }

}

object ScalametaLanguageServer {
  def semanticdbStream(
      implicit s: Scheduler
  ): (Observer.Sync[AbsolutePath], Observable[Database]) = {
    val (subscriber, publisher) =
      Observable.multicast[AbsolutePath](
        MulticastStrategy.Publish,
        OverflowStrategy.ClearBuffer(2)
      )
    val semanticdbPublisher = publisher.map { path =>
      val bytes = Files.readAllBytes(path.toNIO)
      val sdb = schema.Database.parseFrom(bytes)
      val mdb = sdb.toDb(None)
      mdb
    }
    subscriber -> semanticdbPublisher
  }
}
