package scala.meta.languageserver

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.Executors
import scala.concurrent.duration.FiniteDuration
import scala.meta.languageserver.compiler.CompilerConfig
import scala.meta.languageserver.compiler.Cursor
import scala.meta.languageserver.compiler.ScalacProvider
import scala.meta.languageserver.protocol.LanguageClient
import scala.meta.languageserver.protocol.Response
import scala.meta.languageserver.protocol.Services
import scala.meta.languageserver.providers._
import scala.meta.languageserver.refactoring.OrganizeImports
import scala.meta.languageserver.search.SymbolIndex
import com.typesafe.scalalogging.LazyLogging
import io.github.soc.directories.ProjectDirectories
import langserver.messages._
import langserver.types._
import monix.eval.Task
import monix.execution.Cancelable
import monix.execution.Scheduler
import monix.execution.schedulers.SchedulerService
import monix.reactive.MulticastStrategy
import monix.reactive.Observable
import monix.reactive.Observer
import monix.reactive.OverflowStrategy
import org.langmeta.inputs.Input
import org.langmeta.internal.io.PathIO
import org.langmeta.internal.semanticdb.XtensionDatabase
import org.langmeta.internal.semanticdb.schema
import org.langmeta.io.AbsolutePath
import org.langmeta.languageserver.InputEnrichments._
import org.langmeta.semanticdb
import io.circe.Json
import monix.execution.atomic.Atomic

class ScalametaServices(
    cwd: AbsolutePath,
    client: LanguageClient
)(implicit s: Scheduler)
    extends LazyLogging {
  private val tempSourcesDir: AbsolutePath =
    cwd.resolve("target").resolve("sources")
  // Always run the presentation compiler on the same thread
  private val presentationCompilerScheduler: SchedulerService =
    Scheduler(Executors.newFixedThreadPool(1))
  def withPC[A](f: => A): Task[Either[Response.Error, A]] =
    Task(Right(f)).executeOn(presentationCompilerScheduler)
  val (fileSystemSemanticdbSubscriber, fileSystemSemanticdbsPublisher) =
    ScalametaServices.fileSystemSemanticdbStream(cwd)
  val (compilerConfigSubscriber, compilerConfigPublisher) =
    ScalametaServices.compilerConfigStream(cwd)
  val (sourceChangeSubscriber, sourceChangePublisher) =
    Observable.multicast[Input.VirtualFile](
      MulticastStrategy.Publish,
      OverflowStrategy.DropOld(2)
    )
  val (configurationSubscriber, configurationPublisher) =
    ScalametaServices.configurationStream
  val buffers: Buffers = Buffers()
  val symbolIndex: SymbolIndex =
    SymbolIndex(cwd, client, buffers, configurationPublisher)
  val scalacErrorReporter: ScalacErrorReporter =
    new ScalacErrorReporter(client)
  val documentFormattingProvider =
    new DocumentFormattingProvider(configurationPublisher, cwd, client)
  val squiggliesProvider =
    new SquiggliesProvider(configurationPublisher, cwd)
  val scalacProvider = new ScalacProvider
  val interactiveSemanticdbs: Observable[semanticdb.Database] =
    sourceChangePublisher
      .debounce(FiniteDuration(1, "s"))
      .flatMap { input =>
        Observable
          .fromIterable(Semanticdbs.toSemanticdb(input, scalacProvider))
          .executeOn(presentationCompilerScheduler)
      }
  val interactiveSchemaSemanticdbs: Observable[schema.Database] =
    interactiveSemanticdbs.flatMap(db => Observable(db.toSchema(cwd)))
  val metaSemanticdbs: Observable[semanticdb.Database] =
    Observable.merge(
      fileSystemSemanticdbsPublisher.map(_.toDb(sourcepath = None)),
      interactiveSemanticdbs
    )

  // Effects
  val indexedSemanticdbs: Observable[Effects.IndexSemanticdb] =
    Observable
      .merge(fileSystemSemanticdbsPublisher, interactiveSchemaSemanticdbs)
      .map(symbolIndex.indexDatabase)
  val indexedDependencyClasspath: Observable[Effects.IndexSourcesClasspath] =
    compilerConfigPublisher.mapTask(
      c => symbolIndex.indexDependencyClasspath(c.sourceJars)
    )
  val installedCompilers: Observable[Effects.InstallPresentationCompiler] =
    compilerConfigPublisher.map(scalacProvider.loadNewCompilerGlobals)
  val publishDiagnostics: Observable[Effects.PublishSquigglies] =
    metaSemanticdbs.mapTask { db =>
      squiggliesProvider.squigglies(db).map { diagnostics =>
        diagnostics.foreach(client.publishDiagnostics)
        Effects.PublishSquigglies
      }
    }
  val scalacErrors: Observable[Effects.PublishScalacDiagnostics] =
    metaSemanticdbs.map(scalacErrorReporter.reportErrors)
  private var cancelEffects = List.empty[Cancelable]
  val effects: List[Observable[Effects]] = List(
    configurationPublisher.map(_ => Effects.UpdateBuffers),
    indexedDependencyClasspath,
    indexedSemanticdbs,
    installedCompilers,
    publishDiagnostics,
  )

  // TODO(olafur): make it easier to invoke fluid services from tests
  def initialize(
      params: InitializeParams
  ): Task[Either[Response.Error, InitializeResult]] = {
    logger.info(s"Initialized with $cwd, $params")
    LSPLogger.notifications = Some(client)
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
      workspaceSymbolProvider = true,
      renameProvider = true,
      codeActionProvider = true
    )
    Task(Right(InitializeResult(capabilities)))
  }

  // TODO(olafur): make it easier to invoke fluid services from tests
  def shutdown(): Unit = {
    logger.info("Shutting down...")
    cancelEffects.foreach(_.cancel())
  }

  private val shutdownReceived = Atomic(false)
  val services: Services = Services.empty
    .requestAsync[InitializeParams, InitializeResult]("initialize") { params =>
      initialize(params)
    }
    .request[Json, Json]("shutdown") { _ =>
      shutdown()
      shutdownReceived.set(true)
      Json.Null
    }
    .notification[Json]("exit") { _ =>
      // The server should exit with success code 0 if the shutdown request has
      // been received before; otherwise with error code 1
      // -- https://microsoft.github.io/language-server-protocol/specification#exit
      val code = if (shutdownReceived.get) 0 else 1
      logger.info(s"exit($code)")
      sys.exit(code)
    }
    .requestAsync[TextDocumentPositionParams, CompletionList](
      "textDocument/completion"
    ) { params =>
      withPC {
        logger.info("completion")
        scalacProvider.getCompiler(params.textDocument) match {
          case Some(g) =>
            CompletionProvider.completions(
              g,
              toCursor(params.textDocument, params.position)
            )
          case None => CompletionProvider.empty
        }
      }
    }
    .request[TextDocumentPositionParams, List[Location]](
      "textDocument/definition"
    ) { params =>
      DefinitionProvider.definition(
        symbolIndex,
        Uri(params.textDocument.uri),
        params.position,
        tempSourcesDir
      )
    }
    .request[CodeActionParams, List[Command]](
      "textDocument/codeAction"
    ) { params =>
      CodeActionProvider.codeActions(params)
    }
    .notification[DidCloseTextDocumentParams](
      "textDocument/didClose"
    ) { params =>
      buffers.closed(Uri(params.textDocument))
      ()
    }
    .notification[DidOpenTextDocumentParams](
      "textDocument/didOpen"
    ) { params =>
      val input =
        Input.VirtualFile(params.textDocument.uri, params.textDocument.text)
      buffers.changed(input)
      sourceChangeSubscriber.onNext(input)
      ()
    }
    .notification[DidChangeTextDocumentParams](
      "textDocument/didChange"
    ) { params =>
      val changes = params.contentChanges
      require(changes.length == 1, s"Expected one change, got $changes")
      val input = Input.VirtualFile(params.textDocument.uri, changes.head.text)
      buffers.changed(input)
      sourceChangeSubscriber.onNext(input)
      ()
    }
    .notification[DidSaveTextDocumentParams](
      "textDocument/didSave"
    ) { params =>
      ()
    }
    .notification[DidChangeConfigurationParams](
      "workspace/didChangeConfiguration"
    ) { params =>
      params.settings.hcursor.downField("scalameta").as[Configuration] match {
        case Left(err) =>
          client.showMessage(MessageType.Error, err.toString)
        case Right(conf) =>
          logger.info(s"Configuration updated $conf")
          configurationSubscriber.onNext(conf)
      }
    }
    .notification[DidChangeWatchedFilesParams](
      "workspace/didChangeWatchedFiles"
    ) { params =>
      params.changes.foreach {
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
      ()
    }
    .request[TextDocumentPositionParams, List[DocumentHighlight]](
      "textDocument/documentHighlight"
    ) { params =>
      DocumentHighlightProvider.highlight(
        symbolIndex,
        Uri(params.textDocument.uri),
        params.position
      )
    }
    .request[DocumentSymbolParams, List[SymbolInformation]](
      "textDocument/documentSymbol"
    ) { params =>
      val uri = Uri(params.textDocument.uri)
      buffers.source(uri) match {
        case Some(source) => DocumentSymbolProvider.documentSymbols(uri, source)
        case None => DocumentSymbolProvider.empty
      }
    }
    .requestAsync[DocumentFormattingParams, List[TextEdit]](
      "textDocument/formatting"
    ) { params =>
      val uri = Uri(params.textDocument)
      documentFormattingProvider.format(uri.toInput(buffers))
    }
    .request[TextDocumentPositionParams, Hover](
      "textDocument/hover"
    ) { params =>
      HoverProvider.hover(
        symbolIndex,
        Uri(params.textDocument),
        params.position.line,
        params.position.character
      )
    }
    .request[ReferenceParams, List[Location]](
      "textDocument/references"
    ) { params =>
      ReferencesProvider.references(
        symbolIndex,
        Uri(params.textDocument.uri),
        params.position,
        params.context
      )
    }
    .request[RenameParams, WorkspaceEdit](
      "textDocument/rename"
    ) { params =>
      RenameProvider.rename(params, symbolIndex, client)
    }
    .request[TextDocumentPositionParams, SignatureHelp](
      "textDocument/signatureHelp"
    ) { params =>
      scalacProvider.getCompiler(params.textDocument) match {
        case Some(g) =>
          SignatureHelpProvider.signatureHelp(
            g,
            toCursor(params.textDocument, params.position)
          )
        case None => SignatureHelpProvider.empty
      }
    }
    .requestAsync[ExecuteCommandParams, Json](
      "workspace/executeCommand"
    ) { params =>
      logger.info(s"executeCommand $params")
      WorkspaceCommand.withNameOption(params.command) match {
        case None =>
          Task {
            val msg = s"Unknown command ${params.command}"
            logger.error(msg)
            Left(Response.invalidParams(msg))
          }
        case Some(command) =>
          executeCommand(command, params)
      }
    }
    .request[WorkspaceSymbolParams, List[SymbolInformation]](
      "workspace/symbol"
    ) { params =>
      symbolIndex.workspaceSymbols(params.query)
    }

  import WorkspaceCommand._
  val ok = Right(Json.Null)
  private def executeCommand(
      command: WorkspaceCommand,
      params: ExecuteCommandParams
  ): Task[Either[Response.Error, Json]] = command match {
    case ClearIndexCache =>
      Task {
        logger.info("Clearing the index cache")
        ScalametaServices.clearCacheDirectory()
        symbolIndex.clearIndex()
        scalacProvider.allCompilerConfigs.foreach(
          config => symbolIndex.indexDependencyClasspath(config.sourceJars)
        )
        Right(Json.Null)
      }
    case ResetPresentationCompiler =>
      Task {
        logger.info("Resetting all compiler instances")
        scalacProvider.resetCompilers()
        Right(Json.Null)
      }
    case ScalafixUnusedImports =>
      logger.info("Removing unused imports")
      val response = for {
        result <- Task(
          OrganizeImports.removeUnused(params.arguments, symbolIndex)
        )
        applied <- result match {
          case Left(err) => Task.now(Left(err))
          case Right(workspaceEdit) => client.workspaceApplyEdit(workspaceEdit)
        }
      } yield {
        applied match {
          case Left(err) =>
            logger.warn(s"Failed to apply command $err")
            Right(Json.Null)
          case Right(edit) =>
            if (edit.applied) {
              logger.info(s"Successfully applied command $params")
            } else {
              logger.warn(s"Failed to apply edit for command $params")
            }
          case _ =>
        }
        applied.right.map(_ => Json.Null)
      }
      response
  }

  private def toCursor(
      td: TextDocumentIdentifier,
      pos: Position
  ): Cursor = {
    val contents = buffers.read(td)
    val input = Input.VirtualFile(td.uri, contents)
    val offset = input.toOffset(pos)
    Cursor(Uri(td.uri), contents, offset)
  }

  private def loadAllRelevantFilesInThisWorkspace(): Unit = {
    Workspace.initialize(cwd) { path =>
      onChangedFile(path)(_ => ())
    }
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
}

object ScalametaServices extends LazyLogging {
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
    val (subscriber, publisher) = multicast[AbsolutePath]()
    val compilerConfigPublished = publisher
      .map(path => CompilerConfig.fromPath(path))
    subscriber -> compilerConfigPublished
  }

  def fileSystemSemanticdbStream(cwd: AbsolutePath)(
      implicit scheduler: Scheduler
  ): (Observer.Sync[AbsolutePath], Observable[schema.Database]) = {
    val (subscriber, publisher) = multicast[AbsolutePath]()
    val semanticdbPublisher = publisher
      .map(path => Semanticdbs.loadFromFile(semanticdbPath = path, cwd))
    subscriber -> semanticdbPublisher
  }

  def configurationStream(
      implicit scheduler: Scheduler
  ): (Observer.Sync[Configuration], Observable[Configuration]) = {
    val (subscriber, publisher) =
      multicast[Configuration](MulticastStrategy.behavior(Configuration()))
    val configurationPublisher = publisher
    subscriber -> configurationPublisher
  }

  def multicast[A](
      strategy: MulticastStrategy[A] = MulticastStrategy.publish
  )(implicit s: Scheduler): (Observer.Sync[A], Observable[A]) = {
    val (sub, pub) = Observable.multicast[A](strategy)
    (sub, pub.doOnError(onError))
  }

  private def onError(e: Throwable): Unit = {
    logger.error(e.getMessage, e)
  }
}
