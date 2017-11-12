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

import org.eclipse.lsp4j._
import org.eclipse.lsp4j.services._
import org.eclipse.lsp4j.jsonrpc.messages.{Either => JEither}

import java.util.concurrent.CompletableFuture
import java.util.{ List => JList }
import scala.collection.JavaConverters._

import com.typesafe.scalalogging.LazyLogging

import metaserver._

class ScalametaLanguageServer(
    cwd: AbsolutePath,
    stdout: PrintStream
)(implicit s: Scheduler) extends LanguageServer with LanguageClientAware with LazyLogging {

  implicit val workspacePath: AbsolutePath = cwd

  private var client: LanguageClient = _

  val (semanticdbSubscriber, semanticdbPublisher) = ScalametaLanguageServer.semanticdbStream

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
    SymbolIndexer(semanticdbPublisher, logger, client, buffers)

  val scalafix: Linter =
    new Linter(cwd, stdout, client, semanticdbPublisher.doOnError(onError))

  val scalafmt: Formatter = Formatter.classloadScalafmt("1.3.0", stdout)

  val compiler = new Compiler(
    compilerConfigPublisher.doOnError(onError),
    client,
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

  override def connect(client: LanguageClient): Unit = {
    this.client = client
  }

  override def initialize(params: InitializeParams): CompletableFuture[InitializeResult] = CompletableFuture.completedFuture {
    logger.info(s"Initialized with $cwd, ${params.getProcessId}, ${params.getRootUri}, ${params.getCapabilities}")
    toCancel += scalafix.linter.subscribe()
    toCancel += symbol.indexer.subscribe()
    toCancel += compiler.onNewCompilerConfig.subscribe()
    loadAllSemanticdbsInWorkspace()

    val capabilities = new ServerCapabilities
    capabilities.setTextDocumentSync(TextDocumentSyncKind.Full)
    capabilities.setDocumentFormattingProvider(true)
    capabilities.setDocumentSymbolProvider(true)
    capabilities.setHoverProvider(true)
    capabilities.setDefinitionProvider(true)
    capabilities.setCompletionProvider(new CompletionOptions(true, List(".").asJava))

    new InitializeResult(capabilities)
  }

  override def exit(): Unit = {}

  override def shutdown(): CompletableFuture[Object] = CompletableFuture.completedFuture {
    toCancel.foreach(_.cancel())
    new Object
  }

  override def getTextDocumentService(): TextDocumentService = new ScalametaTextDocumentService(this)
  override def getWorkspaceService(): WorkspaceService = new ScalametaWorkspaceService(this)

  def didChangeWatchedFiles(params: DidChangeWatchedFilesParams): Unit =
    params.getChanges.asScala.foreach {
      case change: FileEvent if change.getType == FileChangeType.Created || change.getType == FileChangeType.Changed =>
        Uri.toPath(change.getUri).foreach { path =>
          onChangedFile(path) { _ =>
            logger.warn(s"Unknown file extension for path $path")
          }
        }

      case event =>
        logger.info(s"Unhandled file event: $event")
        ()
    }

  def didOpen(params: DidOpenTextDocumentParams): Unit = {
    val document = params.getTextDocument
    Uri.toPath(document.getUri).foreach(p => buffers.changed(p, document.getText))
  }

  def didChange(params: DidChangeTextDocumentParams): Unit = {
    val document = params.getTextDocument
    params.getContentChanges.asScala.foreach { c =>
      Uri.toPath(document.getUri).foreach(p => buffers.changed(p, c.getText))
    }
  }

  def formatting(params: DocumentFormattingParams): CompletableFuture[JList[_ <: TextEdit]] = CompletableFuture.completedFuture {
    val document = params.getTextDocument
    try {
      val path = Uri.toPath(document.getUri).get
      val contents = buffers.read(path)
      val fullDocumentRange = new Range(
        new Position(0, 0),
        new Position(Int.MaxValue, Int.MaxValue)
      )
      val config = cwd.resolve(".scalafmt.conf")
      if (Files.isRegularFile(config.toNIO)) {
        val formattedContent =
          scalafmt.format(contents, config.toString(), path.toString())
        List(new TextEdit(fullDocumentRange, formattedContent)).asJava
      } else {
        client.showMessage(new MessageParams(MessageType.Info, s"Missing $config"))
        Nil.asJava
      }
    } catch {
      case NonFatal(e) =>
        client.showMessage(new MessageParams(MessageType.Error, e.getMessage))
        logger.error(e.getMessage, e)
        Nil.asJava
    }
  }

  def documentSymbol(params: DocumentSymbolParams): CompletableFuture[JList[_ <: SymbolInformation]] = CompletableFuture.completedFuture {
    val document = params.getTextDocument
    val path = Uri.toPath(document.getUri).get
    symbol.documentSymbols(path.toRelative(cwd)).map {
      case (position, denotation @ Denotation(_, name, signature, _)) =>
        val location = path.toLocation(position)
        val kind = denotation.symbolKind
        new SymbolInformation(name, kind, location, signature)
    }.asJava
  }

  def definition(params: TextDocumentPositionParams): CompletableFuture[JList[_ <: Location]] = CompletableFuture.completedFuture {
    val document = params.getTextDocument
    val position = params.getPosition
    val path = Uri.toPath(document.getUri).get.toRelative(cwd)
    symbol
      .goToDefinition(path, position.getLine, position.getCharacter)
      .fold(List.empty[Location]) { position =>
        cwd.resolve(position.input.syntax).toLocation(position) :: Nil
      }.asJava
  }

  def completion(params: TextDocumentPositionParams): CompletableFuture[JEither[JList[CompletionItem], CompletionList]] = CompletableFuture.completedFuture {
    val document = params.getTextDocument
    val position = params.getPosition

    try {
      val completions = compiler.autocomplete(
        Uri.toPath(document.getUri).get,
        position.getLine,
        position.getCharacter
      )
      JEither.forRight(new CompletionList(
        false,
        completions.map {
          case (signature, name) =>
            val completionItem = new CompletionItem(name)
            completionItem.setDetail(signature)
            completionItem
        }.asJava
      ))
    } catch {
      case NonFatal(e) =>
        onError(e)
        JEither.forRight(new CompletionList(Nil.asJava))
    }
  }

  def hover(params: TextDocumentPositionParams) = CompletableFuture.completedFuture {
    val document = params.getTextDocument
    val position = params.getPosition
    val path = Uri.toPath(document.getUri).get
    compiler.typeAt(path, position.getLine, position.getCharacter) match {
      case None => new Hover(Nil.asJava)
      case Some(tpeName) =>
        new Hover(
          List(
            JEither.forRight[String, MarkedString](new MarkedString("scala", tpeName))
          ).asJava
        )
    }
  }

  // Unimplemented features
  override def initialized(params: InitializedParams) = ???
  def codeAction(params: CodeActionParams): CompletableFuture[JList[_ <: Command]] = ???
  def codeLens(params: CodeLensParams): CompletableFuture[JList[_ <: CodeLens]] = ???
  def didClose(params: DidCloseTextDocumentParams) = ???
  def didSave(params: DidSaveTextDocumentParams) = ???
  def documentHighlight(params: TextDocumentPositionParams): CompletableFuture[JList[_ <: DocumentHighlight]] = ???
  def onTypeFormatting(params: DocumentOnTypeFormattingParams): CompletableFuture[JList[_ <: TextEdit]] = ???
  def rangeFormatting(params: DocumentRangeFormattingParams): CompletableFuture[JList[_ <: TextEdit]] = ???
  def references(params: ReferenceParams): CompletableFuture[JList[_ <: Location]] = ???
  def rename(params: RenameParams): CompletableFuture[WorkspaceEdit] = ???
  def resolveCodeLens(params: CodeLens): CompletableFuture[CodeLens] = ???
  def resolveCompletionItem(params: CompletionItem): CompletableFuture[CompletionItem] = ???
  def signatureHelp(params: TextDocumentPositionParams): CompletableFuture[SignatureHelp] = ???
  def documentLink(params: DocumentLinkParams): CompletableFuture[JList[DocumentLink]] = ???
  def documentLinkResolve(params: DocumentLink): CompletableFuture[DocumentLink] = ???
  def didChangeConfiguration(params: DidChangeConfigurationParams) = ???
  def symbol(params: WorkspaceSymbolParams): CompletableFuture[JList[_ <: SymbolInformation]] = ???
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

