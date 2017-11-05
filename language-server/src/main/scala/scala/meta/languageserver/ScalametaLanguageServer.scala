package scala.meta.languageserver

import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.collection.mutable.ListBuffer
import scala.meta.languageserver.LanguageServerEnrichments._
import scala.util.control.NonFatal
import langserver.core.LanguageServer
import langserver.messages.ClientCapabilities
import langserver.messages.DefinitionResult
import langserver.messages.FileChangeType
import langserver.messages.FileEvent
import langserver.messages.MessageType
import langserver.messages.ServerCapabilities
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
  val buffers: Buffers = Buffers()
  val symbol: SymbolIndexer =
    SymbolIndexer(semanticdbPublisher, logger, connection, buffers)
  val scalafix: Linter =
    new Linter(cwd, stdout, connection, semanticdbPublisher)
  val scalafmt: Formatter = Formatter.classloadScalafmt("1.3.0", stdout)

  private def readFromDisk(path: AbsolutePath): String =
    new String(Files.readAllBytes(path.toNIO), StandardCharsets.UTF_8)

  // TODO(olafur) more holistic error handling story.
  private def unsafe(thunk: => Unit): Unit =
    try thunk
    catch { case NonFatal(e) => logger.error(e.getMessage, e) }

  private def unsafeSeq[T](thunk: => Seq[T]): Seq[T] =
    try thunk
    catch { case NonFatal(e) => logger.error(e.getMessage, e); Nil }

  private val toCancel = ListBuffer.empty[Cancelable]

  override def initialize(
      pid: Long,
      rootPath: String,
      capabilities: ClientCapabilities
  ): ServerCapabilities = {
    logger.info(s"Initialized with $cwd, $pid, $rootPath, $capabilities")
    toCancel += scalafix.linter.subscribe()
    toCancel += symbol.indexer.subscribe()
    ServerCapabilities(
      completionProvider = None,
      definitionProvider = true,
      documentSymbolProvider = true,
      documentFormattingProvider = true
    )
  }

  override def shutdown(): Unit = {
    toCancel.foreach(_.cancel())
  }

  override def onChangeWatchedFiles(changes: Seq[FileEvent]): Unit =
    changes.foreach {
      case FileEvent(
          uri @ Uri(path),
          FileChangeType.Created | FileChangeType.Changed
          ) if uri.endsWith(".semanticdb") =>
        semanticdbSubscriber.onNext(path)
        logger.info(s"$path changed or created. Running scalafix...")
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
      val contents = buffers.read(td.uri).getOrElse(readFromDisk(path))
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
    buffers.changed(td.uri, td.text)

  override def onChangeTextDocument(
      td: VersionedTextDocumentIdentifier,
      changes: Seq[TextDocumentContentChangeEvent]
  ): Unit = {
    changes.foreach { c =>
      buffers.changed(td.uri, c.text)
    }
  }

  override def documentSymbols(
      td: TextDocumentIdentifier
  ): Seq[SymbolInformation] = {
    val path = Uri.toPath(td.uri).get
    symbol.documentSymbols(path.toRelative(cwd)).map {
      case (position, denotation @ Denotation(_, name, signature, _)) =>
        val location = path.toLocation(position)
        val kind = ScalametaLanguageServer.symbolKind(denotation)
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

}

object ScalametaLanguageServer {
  type SymbolKind = Int
  def symbolKind(denotation: Denotation): SymbolKind = {
    import denotation._
    // copy-pasta from metadoc!
    if (isParam || isTypeParam) SymbolKind.Variable // ???
    else if (isVal || isVar) SymbolKind.Variable
    else if (isDef) SymbolKind.Function
    else if (isPrimaryCtor || isSecondaryCtor) SymbolKind.Constructor
    else if (isClass) SymbolKind.Class
    else if (isObject) SymbolKind.Module
    else if (isTrait) SymbolKind.Interface
    else if (isPackage || isPackageObject) SymbolKind.Package
    else if (isType) SymbolKind.Namespace
    else SymbolKind.Variable // ???

  }
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
