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
import org.langmeta.lsp.Window.showMessage
import org.langmeta.lsp.{
  Lifecycle => lc,
  TextDocument => td,
  Workspace => ws,
  _
}
import MonixEnrichments._
import org.langmeta.jsonrpc.Response
import org.langmeta.jsonrpc.Services
import scala.meta.languageserver.providers._
import scala.meta.languageserver.refactoring.OrganizeImports
import scala.meta.languageserver.sbtserver.Sbt
import scala.meta.languageserver.sbtserver.SbtServer
import scala.meta.languageserver.search.SymbolIndex
import scala.util.Failure
import scala.util.Success
import scala.util.control.NonFatal
import com.typesafe.scalalogging.LazyLogging
import io.circe.Json
import io.github.soc.directories.ProjectDirectories
import monix.eval.Task
import monix.execution.Cancelable
import monix.execution.Scheduler
import monix.execution.atomic.Atomic
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
import org.langmeta.lsp.LanguageClient
import org.langmeta.semanticdb

class ScalametaServices(
    cwd: AbsolutePath,
    client: LanguageClient,
    s: MSchedulers,
) extends LazyLogging {
  implicit val scheduler: Scheduler = s.global
  implicit val languageClient: LanguageClient = client
  private var sbtServer: Option[SbtServer] = None
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
    SymbolIndex(cwd, buffers, configurationPublisher)
  val scalacErrorReporter: ScalacErrorReporter =
    new ScalacErrorReporter()
  val documentFormattingProvider =
    new DocumentFormattingProvider(configurationPublisher, cwd)
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
        diagnostics.foreach(td.publishDiagnostics.notify)
        Effects.PublishSquigglies
      }
    }
  val scalacErrors: Observable[Effects.PublishScalacDiagnostics] =
    metaSemanticdbs.map(scalacErrorReporter.reportErrors)
  val sbtServerEnabled: () => Boolean =
    configurationPublisher
      .focus(_.sbt.enabled)
      .doOnNext {
        case true =>
          connectToSbtServer()
        case false =>
          sbtServer.foreach(_.runningServer.cancel())
          sbtServer = None
      }
      .toFunction0()
  val latestConfig: () => Configuration =
    configurationPublisher.toFunction0()
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
    val commands = WorkspaceCommand.values.map(_.entryName)
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
      executeCommandProvider = ExecuteCommandOptions(commands),
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
    .requestAsync(lc.initialize)(initialize)
    .notification(lc.initialized) { _ =>
      logger.info("Client is initialized")
    }
    .request(lc.shutdown) { _ =>
      shutdown()
      shutdownReceived.set(true)
      Json.Null
    }
    .notification(lc.exit) { _ =>
      // The server should exit with success code 0 if the shutdown request has
      // been received before; otherwise with error code 1
      // -- https://microsoft.github.io/language-server-protocol/specification#exit
      val code = if (shutdownReceived.get) 0 else 1
      logger.info(s"exit($code)")
      sys.exit(code)
    }
    .requestAsync(td.completion) { params =>
      withPC {
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
    .request(td.definition) { params =>
      DefinitionProvider.definition(
        symbolIndex,
        Uri(params.textDocument.uri),
        params.position,
        tempSourcesDir
      )
    }
    .request(td.codeAction) { params =>
      CodeActionProvider.codeActions(params)
    }
    .notification(td.didClose) { params =>
      buffers.closed(Uri(params.textDocument))
      ()
    }
    .notification(td.didOpen) { params =>
      val input =
        Input.VirtualFile(params.textDocument.uri, params.textDocument.text)
      buffers.changed(input)
      sourceChangeSubscriber.onNext(input)
      ()
    }
    .notification(td.didChange) { params =>
      val changes = params.contentChanges
      require(changes.length == 1, s"Expected one change, got $changes")
      val input = Input.VirtualFile(params.textDocument.uri, changes.head.text)
      buffers.changed(input)
      sourceChangeSubscriber.onNext(input)
      ()
    }
    .notification(td.willSave) { _ =>
      ()
    }
    .notification(td.didSave) { _ =>
      if (sbtServerEnabled()) {
        sbtCompile()
      }
    }
    .notification(ws.didChangeConfiguration) { params =>
      params.settings.hcursor.downField("metals").as[Configuration] match {
        case Left(err) =>
          showMessage.notify(
            ShowMessageParams(MessageType.Error, err.toString)
          )
        case Right(conf) =>
          logger.info(s"Configuration updated $conf")
          configurationSubscriber.onNext(conf)
      }
    }
    .notification(ws.didChangeWatchedFiles) { params =>
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
    .request(td.documentHighlight) { params =>
      DocumentHighlightProvider.highlight(
        symbolIndex,
        Uri(params.textDocument.uri),
        params.position
      )
    }
    .request(td.documentSymbol) { params =>
      val uri = Uri(params.textDocument.uri)
      buffers.source(uri) match {
        case Some(source) => DocumentSymbolProvider.documentSymbols(uri, source)
        case None => DocumentSymbolProvider.empty
      }
    }
    .requestAsync(td.formatting) { params =>
      val uri = Uri(params.textDocument)
      documentFormattingProvider.format(uri.toInput(buffers))
    }
    .request(td.hover) { params =>
      HoverProvider.hover(
        symbolIndex,
        Uri(params.textDocument),
        params.position.line,
        params.position.character
      )
    }
    .request(td.references) { params =>
      ReferencesProvider.references(
        symbolIndex,
        Uri(params.textDocument.uri),
        params.position,
        params.context
      )
    }
    .request(td.rename) { params =>
      RenameProvider.rename(params, symbolIndex)
    }
    .request(td.signatureHelp) { params =>
      scalacProvider.getCompiler(params.textDocument) match {
        case Some(g) =>
          SignatureHelpProvider.signatureHelp(
            g,
            toCursor(params.textDocument, params.position)
          )
        case None => SignatureHelpProvider.empty
      }
    }
    .requestAsync(ws.executeCommand) { params =>
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
    .request(ws.symbol) { params =>
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
          case Right(workspaceEdit) => ws.applyEdit.request(workspaceEdit)
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
    case SbtConnect =>
      Task {
        if (!sbtServerEnabled()) {
          showMessage.error("Set scalameta.sbt.enabled=true to use sbt server.")
        } else {
          connectToSbtServer()
        }
        Right(Json.Null)
      }
  }

  private def sbtCompile(): Unit = sbtServer match {
    case None => ()
    case Some(sbt) =>
      // TODO(olafur) support running other commands than "compile"
      // running top-level "compile" is sub-optimal for large builds
      // especially cross-built builds with scala.js/native
      Sbt
        .exec(latestConfig().sbt.command)(sbt.client)
        .onErrorRecover {
          case NonFatal(err) =>
            // TODO(olafur) figure out why this "broken pipe" is not getting
            // caught here.
            logger.error("Failed to send sbt compile", err)
            showMessage.warn(
              "Lost connection to sbt server. " +
                "Restart the sbt session and run the 'Re-connect to sbt server' command"
            )
        }
        .runAsync
  }

  private def connectToSbtServer(): Unit = {
    sbtServer.foreach(_.runningServer.cancel())
    val services = SbtServer.forwardingServices(client)
    SbtServer.connect(cwd, services)(s.sbt).foreach {
      case Left(err) => showMessage.error(err)
      case Right(server) =>
        val msg = "Established connection with sbt server 😎"
        logger.info(msg)
        showMessage.info(msg)
        sbtServer = Some(server)
        cancelEffects ::= server.runningServer
        server.runningServer.onComplete {
          case Failure(err) =>
            logger.error(s"Unexpected failure from sbt server connection", err)
            showMessage.error(err.getMessage)
          case Success(()) =>
            sbtServer = None
            showMessage.warn("Disconnected from sbt server")
        }
        sbtCompile() // run compile right away.
    }
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
