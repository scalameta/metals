package scala.meta.languageserver

import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.nio.file.Files
import scala.collection.mutable.ListBuffer
import scala.meta.languageserver.ScalametaEnrichments._
import scala.meta.languageserver.compiler.CompilerConfig
import scala.meta.languageserver.compiler.Cursor
import scala.meta.languageserver.compiler.ScalacProvider
import scala.meta.languageserver.search.SymbolIndex
import scala.meta.languageserver.providers._
import scalafix.internal.util.EagerInMemorySemanticdbIndex
import com.typesafe.scalalogging.LazyLogging
import io.github.soc.directories.ProjectDirectories
import langserver.core.LanguageServer
import langserver.messages.ClientCapabilities
import langserver.messages.CompletionOptions
import langserver.messages.DefinitionResult
import langserver.messages.ReferencesResult
import langserver.messages.DocumentHighlightResult
import langserver.messages.Hover
import langserver.messages.ResultResponse
import langserver.messages.ServerCapabilities
import langserver.messages.SignatureHelpOptions
import langserver.types._
import monix.execution.Cancelable
import monix.execution.Scheduler
import monix.reactive.MulticastStrategy
import monix.reactive.Observable
import monix.reactive.Observer
import org.langmeta.internal.io.PathIO
import org.langmeta.internal.semanticdb.schema.Database
import org.langmeta.io.AbsolutePath
import org.langmeta.semanticdb
import scala.meta.interactive.InteractiveSemanticdb
import scala.tools.nsc.interactive.Global

case class ServerConfig(
    cwd: AbsolutePath,
    setupScalafmt: Boolean = true,
    // TODO(olafur): re-enable indexJDK after https://github.com/scalameta/language-server/issues/43 is fixed
    indexJDK: Boolean = false,
    indexClasspath: Boolean = true
)

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
  val (interactiveSemanticdbSubscriber, interactiveSemanticdbPublisher) =
    ScalametaLanguageServer.interactiveSemanticdbStream
  val (compilerConfigSubscriber, compilerConfigPublisher) =
    ScalametaLanguageServer.compilerConfigStream(cwd)
  val buffers: Buffers = Buffers()
  val scalac: ScalacProvider = new ScalacProvider(config)
  val symbolIndex: SymbolIndex = SymbolIndex(cwd, connection, buffers, config)
  val scalafix: Linter = new Linter(cwd, stdout, connection)
  val scalacErrorReporter: ScalacErrorReporter =  new ScalacErrorReporter(connection)
  val metaSemanticdbs: Observable[semanticdb.Database] =
    Observable.merge(
      fileSystemSemanticdbsPublisher.map(_.toDb(sourcepath = None)),
      interactiveSemanticdbPublisher
    )
  val scalafmt: Formatter =
    if (config.setupScalafmt) Formatter.classloadScalafmt("1.3.0")
    else Formatter.noop

  // Effects
  val indexedFileSystemSemanticdbs: Observable[Effects.IndexSemanticdb] =
    fileSystemSemanticdbsPublisher.map(symbolIndex.indexDatabase)
  val indexedDependencyClasspath: Observable[Effects.IndexSourcesClasspath] =
    compilerConfigPublisher.map(
      c => symbolIndex.indexDependencyClasspath(c.sourceJars)
    )
  val installedCompilers: Observable[Effects.InstallPresentationCompiler] =
    compilerConfigPublisher.map(scalac.loadNewCompilerGlobals)
  val scalafixNotifications: Observable[Effects.PublishLinterDiagnostics] =
    metaSemanticdbs.map(scalafix.reportLinterMessages)
  val scalacErrors: Observable[Effects.PublishScalacDiagnostics] =
    metaSemanticdbs.map(scalacErrorReporter.reportErrors)
  private var cancelEffects = List.empty[Cancelable]
  val effects: List[Observable[Effects]] = List(
    indexedDependencyClasspath,
    indexedFileSystemSemanticdbs,
    installedCompilers,
    scalafixNotifications,
    scalacErrors
  )

  private def loadAllRelevantFilesInThisWorkspace(): Unit = {
    Workspace.initialize(cwd) { path =>
      onChangedFile(path)(_ => ())
    }
  }

  override def initialize(
      pid: Long,
      rootPath: String,
      capabilities: ClientCapabilities
  ): ServerCapabilities = {
    logger.info(s"Initialized with $cwd, $pid, $rootPath, $capabilities")
    cancelEffects = effects.map(_.subscribe())
    loadAllRelevantFilesInThisWorkspace()
    ServerCapabilities(
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
      hoverProvider = true
    )
  }

  override def shutdown(): Unit = {
    cancelEffects.foreach(_.cancel())
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
        onChangedFile(path) { _ =>
          logger.warn(s"Unknown file extension for path $path")
        }

      case event =>
        logger.warn(s"Unhandled file event: $event")
        ()
    }

  override def documentFormattingRequest(
      td: TextDocumentIdentifier,
      options: FormattingOptions
  ): List[TextEdit] = {
    val path = Uri.toPath(td.uri).get
    val contents = buffers.read(path)
    val fullDocumentRange = Range(
      start = Position(0, 0),
      end = Position(Int.MaxValue, Int.MaxValue)
    )
    val config = cwd.resolve(".scalafmt.conf")
    if (Files.isRegularFile(config.toNIO)) {
      val formattedContent =
        scalafmt.format(contents, path.toString(), config)
      List(TextEdit(fullDocumentRange, formattedContent))
    } else {
      connection.showMessage(MessageType.Info, s"Missing $config")
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
    scalac.getCompiler(td).foreach { compiler =>
      interactiveSemanticdbSubscriber.onNext((compiler, td, buffers.read(td)))
    }
  }

  override def onCloseTextDocument(td: TextDocumentIdentifier): Unit =
    Uri.toPath(td.uri).foreach(buffers.closed)

  override def documentSymbols(
      td: TextDocumentIdentifier
  ): List[SymbolInformation] = {
    val path = Uri.toPath(td.uri).get
    buffers.source(path) match {
      case Some(source) => DocumentSymbolProvider.documentSymbols(path, source)
      case None => Nil
    }
  }

  override def gotoDefinitionRequest(
      td: TextDocumentIdentifier,
      position: Position
  ): DefinitionResult =
    DefinitionProvider.definition(
      symbolIndex,
      Uri.toPath(td.uri).get,
      position,
      tempSourcesDir
    )

  override def referencesRequest(
      td: TextDocumentIdentifier,
      position: Position,
      context: ReferenceContext
  ): ReferencesResult =
    ReferencesProvider.references(
      symbolIndex,
      Uri.toPath(td.uri).get,
      position,
      context
    )

  override def documentHighlightRequest(
      td: TextDocumentIdentifier,
      position: Position
  ): DocumentHighlightResult =
    DocumentHighlightProvider.highlight(
      symbolIndex,
      Uri.toPath(td.uri).get,
      position
    )

  override def signatureHelpRequest(
      td: TextDocumentIdentifier,
      pos: Position
  ): SignatureHelp = {
    scalac.getCompiler(td) match {
      case Some(g) => SignatureHelpProvider.signatureHelp(g, toPoint(td, pos))
      case None => SignatureHelpProvider.empty
    }
  }

  override def completionRequest(
      td: TextDocumentIdentifier,
      pos: Position
  ): ResultResponse = {
    scalac.getCompiler(td) match {
      case Some(g) => CompletionProvider.completions(g, toPoint(td, pos))
      case None => CompletionProvider.empty
    }
  }

  override def hoverRequest(
      td: TextDocumentIdentifier,
      pos: Position
  ): Hover = {
    scalac.getCompiler(td) match {
      case Some(g) => HoverProvider.hover(g, toPoint(td, pos))
      case None => HoverProvider.empty
    }
  }

  private def toPoint(
      td: TextDocumentIdentifier,
      pos: Position
  ): Cursor = {
    val contents = buffers.read(td)
    val offset = Positions.positionToOffset(
      td.uri,
      contents,
      pos.line,
      pos.character
    )
    Cursor(td.uri, contents, offset)
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
  ): (Observer.Sync[AbsolutePath], Observable[Database]) = {
    val (subscriber, publisher) = multicast[AbsolutePath]
    val semanticdbPublisher = publisher
      .map(path => Semanticdbs.loadFromFile(semanticdbPath = path, cwd))
    subscriber -> semanticdbPublisher
  }

  def interactiveSemanticdbStream(
      implicit scheduler: Scheduler
  ): (Observer.Sync[(Global, VersionedTextDocumentIdentifier, String)], Observable[semanticdb.Database]) = {
    val (subscriber, publisher) = multicast[(Global, VersionedTextDocumentIdentifier, String)]
    val semanticdbPublisher = publisher.map { case (compiler, td, content) =>
      Semanticdbs.loadFromTextDocument(compiler, td, content)
    }
    subscriber -> semanticdbPublisher
  }

  private def multicast[T](implicit s: Scheduler) = {
    val (sub, pub) = Observable.multicast[T](MulticastStrategy.Publish)
    (sub, pub.doOnError(onError))
  }

  private def onError(e: Throwable): Unit = {
    logger.error(e.getMessage, e)
  }
}
