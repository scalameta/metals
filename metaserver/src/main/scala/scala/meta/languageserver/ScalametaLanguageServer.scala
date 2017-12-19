package scala.meta.languageserver

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import scala.concurrent.duration.FiniteDuration
import scala.meta.languageserver.compiler.CompilerConfig
import scala.meta.languageserver.compiler.Cursor
import scala.meta.languageserver.compiler.ScalacProvider
import scala.meta.languageserver.providers._
import scala.meta.languageserver.search.SymbolIndex
import org.langmeta.languageserver.InputEnrichments._
import com.typesafe.scalalogging.LazyLogging
import io.github.soc.directories.ProjectDirectories
import langserver.core.LanguageServer
import langserver.messages.CompletionList
import langserver.messages.CompletionOptions
import langserver.messages.DefinitionResult
import langserver.messages.DocumentFormattingResult
import langserver.messages.DocumentHighlightResult
import langserver.messages.DocumentSymbolParams
import langserver.messages.DocumentSymbolResult
import langserver.messages.Hover
import langserver.messages.InitializeParams
import langserver.messages.InitializeResult
import langserver.messages.ReferencesResult
import langserver.messages.ServerCapabilities
import langserver.messages.ShutdownResult
import langserver.messages.SignatureHelpOptions
import langserver.messages.SignatureHelpResult
import langserver.messages.TextDocumentCompletionRequest
import langserver.messages.TextDocumentDefinitionRequest
import langserver.messages.TextDocumentDocumentHighlightRequest
import langserver.messages.TextDocumentFormattingRequest
import langserver.messages.TextDocumentHoverRequest
import langserver.messages.TextDocumentReferencesRequest
import langserver.messages.TextDocumentSignatureHelpRequest
import langserver.messages.WorkspaceExecuteCommandRequest
import langserver.messages.ExecuteCommandOptions
import langserver.messages.WorkspaceSymbolRequest
import langserver.messages.WorkspaceSymbolResult
import langserver.types._
import monix.eval.Task
import monix.execution.Cancelable
import monix.execution.Scheduler
import monix.reactive.MulticastStrategy
import monix.reactive.Observable
import monix.reactive.Observer
import monix.reactive.OverflowStrategy
import org.langmeta.inputs.Input
import org.langmeta.internal.io.PathIO
import org.langmeta.internal.semanticdb.XtensionDatabase
import org.langmeta.internal.semanticdb.schema
import org.langmeta.io.AbsolutePath
import org.langmeta.semanticdb

case class ServerConfig(
    cwd: AbsolutePath,
    setupScalafmt: Boolean = true,
    // TODO(olafur): re-enable indexJDK after https://github.com/scalameta/language-server/issues/43 is fixed
    indexJDK: Boolean = false,
    indexClasspath: Boolean = true
) {
  lazy val configDir: AbsolutePath = cwd.resolve(".metaserver")
  lazy val logFile: File = configDir.resolve("metaserver.log").toFile
}

class ScalametaLanguageServer(
    config: ServerConfig,
    lspIn: InputStream,
    lspOut: OutputStream,
    stdout: PrintStream
)(implicit s: Scheduler)
    extends LanguageServer(lspIn, lspOut) {
  implicit val cwd: AbsolutePath = config.cwd
  private val tempSourcesDir: AbsolutePath =
    cwd.resolve("target").resolve("sources")
  val (fileSystemSemanticdbSubscriber, fileSystemSemanticdbsPublisher) =
    ScalametaLanguageServer.fileSystemSemanticdbStream(cwd)
  val (compilerConfigSubscriber, compilerConfigPublisher) =
    ScalametaLanguageServer.compilerConfigStream(cwd)
  val (sourceChangeSubscriber, sourceChangePublisher) =
    Observable.multicast[Input.VirtualFile](
      MulticastStrategy.Publish,
      OverflowStrategy.DropOld(2)
    )
  val buffers: Buffers = Buffers()
  val scalac: ScalacProvider = new ScalacProvider(config)
  val symbolIndex: SymbolIndex = SymbolIndex(cwd, connection, buffers, config)
  val scalafix: Linter = new Linter(cwd, stdout)
  val scalacErrorReporter: ScalacErrorReporter = new ScalacErrorReporter(
    connection
  )
  val interactiveSemanticdbs: Observable[semanticdb.Database] =
    sourceChangePublisher
      .debounce(FiniteDuration(1, "s"))
      .flatMap { input =>
        Observable.fromIterable(Semanticdbs.toSemanticdb(input, scalac))
      }
  val interactiveSchemaSemanticdbs: Observable[schema.Database] =
    interactiveSemanticdbs.flatMap(db => Observable(db.toSchema(cwd)))
  val metaSemanticdbs: Observable[semanticdb.Database] =
    Observable.merge(
      fileSystemSemanticdbsPublisher.map(_.toDb(sourcepath = None)),
      interactiveSemanticdbs
    )
  val scalafmt: Formatter =
    if (config.setupScalafmt) Formatter.classloadScalafmt("1.3.0")
    else Formatter.noop

  // Effects
  val indexedSemanticdbs: Observable[Effects.IndexSemanticdb] =
    Observable
      .merge(fileSystemSemanticdbsPublisher, interactiveSchemaSemanticdbs)
      .map(symbolIndex.indexDatabase)
  val indexedDependencyClasspath: Observable[Effects.IndexSourcesClasspath] =
    compilerConfigPublisher.map(
      c => symbolIndex.indexDependencyClasspath(c.sourceJars)
    )
  val installedCompilers: Observable[Effects.InstallPresentationCompiler] =
    compilerConfigPublisher.map(scalac.loadNewCompilerGlobals)
  val publishDiagnostics: Observable[Effects.PublishSquigglies] =
    metaSemanticdbs.map { db =>
      val diagnostics = SquiggliesProvider.squigglies(db, scalafix)
      diagnostics.foreach(connection.sendNotification)
      Effects.PublishSquigglies
    }
  val scalacErrors: Observable[Effects.PublishScalacDiagnostics] =
    metaSemanticdbs.map(scalacErrorReporter.reportErrors)
  private var cancelEffects = List.empty[Cancelable]
  val effects: List[Observable[Effects]] = List(
    indexedDependencyClasspath,
    indexedSemanticdbs,
    installedCompilers,
    publishDiagnostics,
  )

  private def loadAllRelevantFilesInThisWorkspace(): Unit = {
    Workspace.initialize(cwd) { path =>
      onChangedFile(path)(_ => ())
    }
  }

  override def initialize(
      request: InitializeParams
  ): Task[InitializeResult] = Task {
    logger.info(s"Initialized with $cwd, $request")
    cancelEffects = effects.map(_.subscribe())
    loadAllRelevantFilesInThisWorkspace()
    val capabilities = ServerCapabilities(
      completionProvider = Some(
        CompletionOptions(
          resolveProvider = false,
          triggerCharacters = "." :: Nil
        )
      ),
      signatureHelpProvider = Some(
        SignatureHelpOptions(
          triggerCharacters = "(" :: Nil
        )
      ),
      definitionProvider = true,
      referencesProvider = true,
      documentHighlightProvider = true,
      documentSymbolProvider = true,
      documentFormattingProvider = true,
      hoverProvider = true,
      executeCommandProvider =
        ExecuteCommandOptions(WorkspaceCommand.values.map(_.entryName)),
      workspaceSymbolProvider = true
    )
    InitializeResult(capabilities)
  }

  override def shutdown(): Task[ShutdownResult] = Task {
    logger.info("Shutting down...")
    cancelEffects.foreach(_.cancel())
    connection.cancelAllActiveRequests()
    ShutdownResult()
  }

  private def onChangedFile(
      path: AbsolutePath
  )(fallback: AbsolutePath => Unit): Unit = {
    val name = PathIO.extension(path.toNIO)
    logger.info(s"File $path changed, extension=$name")
    name match {
      case "semanticdb" => fileSystemSemanticdbSubscriber.onNext(path)
      case "compilerconfig" => compilerConfigSubscriber.onNext(path)
      case _ => fallback(path)
    }
  }

  override def onChangeWatchedFiles(changes: Seq[FileEvent]): Unit =
    changes.foreach {
      case FileEvent(
          Uri(path),
          FileChangeType.Created | FileChangeType.Changed
          ) =>
        onChangedFile(path.toAbsolutePath) { _ =>
          logger.warn(s"Unknown file extension for path $path")
        }

      case event =>
        logger.warn(s"Unhandled file event: $event")
        ()
    }

  override def completion(
      request: TextDocumentCompletionRequest
  ): Task[CompletionList] = Task {
    logger.info("completion")
    scalac.getCompiler(request.params.textDocument) match {
      case Some(g) =>
        CompletionProvider.completions(
          g,
          toPoint(request.params.textDocument, request.params.position)
        )
      case None => CompletionProvider.empty
    }
  }

  override def definition(
      request: TextDocumentDefinitionRequest
  ): Task[DefinitionResult] = Task {
    DefinitionProvider.definition(
      symbolIndex,
      Uri(request.params.textDocument.uri),
      request.params.position,
      tempSourcesDir
    )
  }

  override def documentHighlight(
      request: TextDocumentDocumentHighlightRequest
  ): Task[DocumentHighlightResult] = Task {
    DocumentHighlightProvider.highlight(
      symbolIndex,
      Uri(request.params.textDocument.uri),
      request.params.position
    )
  }

  override def documentSymbol(
      request: DocumentSymbolParams
  ): Task[DocumentSymbolResult] = Task {
    val uri = Uri(request.textDocument.uri)
    buffers.source(uri) match {
      case Some(source) => DocumentSymbolProvider.documentSymbols(uri, source)
      case None => DocumentSymbolProvider.empty
    }
  }

  override def formatting(
      request: TextDocumentFormattingRequest
  ): Task[DocumentFormattingResult] = Task {
    val uri = Uri(request.params.textDocument)
    DocumentFormattingProvider.format(uri.toInput(buffers), scalafmt, cwd)
  }

  override def hover(
      request: TextDocumentHoverRequest
  ): Task[Hover] = Task {
    scalac.getCompiler(request.params.textDocument) match {
      case Some(g) =>
        HoverProvider.hover(
          g,
          toPoint(request.params.textDocument, request.params.position)
        )
      case None => HoverProvider.empty
    }
  }

  override def references(
      request: TextDocumentReferencesRequest
  ): Task[ReferencesResult] = Task {
    ReferencesProvider.references(
      symbolIndex,
      Uri(request.params.textDocument.uri),
      request.params.position,
      request.params.context
    )
  }

  override def signatureHelp(
      request: TextDocumentSignatureHelpRequest
  ): Task[SignatureHelpResult] = Task {
    scalac.getCompiler(request.params.textDocument) match {
      case Some(g) =>
        SignatureHelpProvider.signatureHelp(
          g,
          toPoint(request.params.textDocument, request.params.position)
        )
      case None => SignatureHelpProvider.empty
    }
  }

  override def onOpenTextDocument(td: TextDocumentItem): Unit = {
    val input = Input.VirtualFile(td.uri, td.text)
    buffers.changed(input)
    sourceChangeSubscriber.onNext(input)
  }

  override def executeCommand(request: WorkspaceExecuteCommandRequest) = Task {
    import WorkspaceCommand._
    WorkspaceCommand
      .withNameOption(request.params.command)
      .fold(logger.error(s"Unknown command ${request.params.command}")) {
        case ClearIndexCache =>
          logger.info("Clearing the index cache")
          ScalametaLanguageServer.clearCacheDirectory()
        case ResetPresentationCompiler =>
          logger.info("Resetting all compiler instances")
          scalac.resetCompilers()
      }
  }

  override def workspaceSymbol(
      request: WorkspaceSymbolRequest
  ): Task[WorkspaceSymbolResult] = Task {
    logger.info(s"Workspace $request")
    WorkspaceSymbolResult(symbolIndex.workspaceSymbols(request.params.query))
  }

  override def onChangeTextDocument(
      td: VersionedTextDocumentIdentifier,
      changes: Seq[TextDocumentContentChangeEvent]
  ): Unit = {
    require(changes.length == 1, s"Expected one change, got $changes")
    val input = Input.VirtualFile(td.uri, changes.head.text)
    buffers.changed(input)
    sourceChangeSubscriber.onNext(input)
  }

  override def onCloseTextDocument(td: TextDocumentIdentifier): Unit =
    buffers.closed(Uri(td))

  private def toPoint(
      td: TextDocumentIdentifier,
      pos: Position
  ): Cursor = {
    val contents = buffers.read(td)
    val input = Input.VirtualFile(td.uri, contents)
    val offset = input.toOffset(pos)
    Cursor(Uri(td.uri), contents, offset)
  }

}

object ScalametaLanguageServer extends LazyLogging {
  lazy val cacheDirectory: AbsolutePath = {
    val path = AbsolutePath(
      ProjectDirectories.fromProjectName("metaserver").projectCacheDir
    )
    Files.createDirectories(path.toNIO)
    path
  }

  def clearCacheDirectory(): Unit =
    Files.walkFileTree(
      cacheDirectory.toNIO,
      new SimpleFileVisitor[Path] {
        override def visitFile(
            file: Path,
            attr: BasicFileAttributes
        ): FileVisitResult = {
          Files.delete(file)
          FileVisitResult.CONTINUE
        }
        override def postVisitDirectory(
            dir: Path,
            exc: IOException
        ): FileVisitResult = {
          Files.delete(dir)
          FileVisitResult.CONTINUE
        }
      }
    )

  def compilerConfigStream(cwd: AbsolutePath)(
      implicit scheduler: Scheduler
  ): (Observer.Sync[AbsolutePath], Observable[CompilerConfig]) = {
    val (subscriber, publisher) = multicast[AbsolutePath]
    val compilerConfigPublished = publisher
      .map(path => CompilerConfig.fromPath(path))
    subscriber -> compilerConfigPublished
  }

  def fileSystemSemanticdbStream(cwd: AbsolutePath)(
      implicit scheduler: Scheduler
  ): (Observer.Sync[AbsolutePath], Observable[schema.Database]) = {
    val (subscriber, publisher) = multicast[AbsolutePath]
    val semanticdbPublisher = publisher
      .map(path => Semanticdbs.loadFromFile(semanticdbPath = path, cwd))
    subscriber -> semanticdbPublisher
  }

  def multicast[T](implicit s: Scheduler) = {
    val (sub, pub) = Observable.multicast[T](MulticastStrategy.Publish)
    (sub, pub.doOnError(onError))
  }

  private def onError(e: Throwable): Unit = {
    logger.error(e.getMessage, e)
  }
}
