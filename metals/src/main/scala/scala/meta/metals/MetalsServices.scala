package scala.meta.metals

import io.circe.Json
import io.github.soc.directories.ProjectDirectories
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.Executors
import monix.eval.Coeval
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
import org.langmeta.internal.semanticdb.schema
import org.langmeta.io.AbsolutePath
import org.langmeta.languageserver.InputEnrichments._
import scala.meta.jsonrpc.LanguageClient
import scala.meta.jsonrpc.MonixEnrichments._
import scala.meta.jsonrpc.Response
import scala.meta.jsonrpc.Services
import scala.meta.lsp.Window.showMessage
import scala.meta.lsp.{TextDocument => td}
import scala.meta.lsp.{Workspace => ws}
import scala.meta.lsp.{Lifecycle => lc}
import scala.meta.lsp._
import scala.meta.metals.compiler.CompilerConfig
import scala.meta.metals.compiler.Cursor
import scala.meta.metals.providers._
import scala.meta.metals.sbtserver.Sbt
import scala.meta.metals.sbtserver.SbtServer
import scala.meta.metals.search.SymbolIndex
import scala.util.Failure
import scala.util.Success
import scala.util.control.NonFatal

class MetalsServices(
    cwd: AbsolutePath,
    client: LanguageClient,
    s: MSchedulers
) {
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
    MetalsServices.fileSystemSemanticdbStream(cwd)
  val (compilerConfigSubscriber, compilerConfigPublisher) =
    MetalsServices.compilerConfigStream(cwd)
  val (sourceChangeSubscriber, sourceChangePublisher) =
    Observable.multicast[Input.VirtualFile](
      MulticastStrategy.Publish,
      OverflowStrategy.DropOld(2)
    )
  val (configurationSubscriber, configurationPublisher) =
    MetalsServices.configurationStream
  val latestConfig: () => Configuration =
    configurationPublisher.toFunction0()
  val buffers: Buffers = Buffers()
  val symbolIndex: SymbolIndex =
    SymbolIndex(cwd, buffers, configurationPublisher)
  val documentFormattingProvider =
    new DocumentFormattingProvider(configurationPublisher, cwd)

  // Effects
  val indexedSemanticdbs: Observable[Effects.IndexSemanticdb] =
    fileSystemSemanticdbsPublisher.map(symbolIndex.indexDatabase)
  val indexedDependencyClasspath: Observable[Effects.IndexSourcesClasspath] =
    compilerConfigPublisher.mapTask { config =>
      symbolIndex.indexDependencyClasspath(config.sourceJars)
    }
  private var cancelEffects = List.empty[Cancelable]
  val effects: List[Observable[Effects]] = List(
    configurationPublisher.map(_ => Effects.UpdateBuffers),
    indexedDependencyClasspath,
    indexedSemanticdbs,
  ).map(_.doOnError(MetalsServices.onError))

  // TODO(olafur): make it easier to invoke fluid services from tests
  def initialize(
      params: InitializeParams
  ): Task[Either[Response.Error, InitializeResult]] = {
    scribe.info(s"Initialized with $cwd, $params")
    LSPLogger.client = Some(client)
    cancelEffects = effects.map(_.subscribe())
    Workspace.initialize(cwd) { onChangedFile(_)(()) }
    val commands = WorkspaceCommand.values.map(_.entryName)
    val capabilities = ServerCapabilities(
      textDocumentSync = Some(
        TextDocumentSyncOptions(
          openClose = Some(true),
          change = Some(TextDocumentSyncKind.Full),
          willSave = Some(false),
          willSaveWaitUntil = Some(true),
          save = Some(
            SaveOptions(
              includeText = Some(true)
            )
          )
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
      codeActionProvider = false
    )
    Task(Right(InitializeResult(capabilities)))
  }

  // TODO(olafur): make it easier to invoke fluid services from tests
  def shutdown(): Unit = {
    scribe.info("Shutting down...")
    cancelEffects.foreach(_.cancel())
  }

  private val shutdownReceived = Atomic(false)
  val services: Services = Services
    .empty(scribe.Logger.root)
    .requestAsync(lc.initialize)(initialize)
    .notification(lc.initialized) { _ =>
      scribe.info("Client is initialized")
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
      scribe.info(s"exit($code)")
      sys.exit(code)
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
    .requestAsync(td.willSaveWaitUntil) { params =>
      params.reason match {
        case TextDocumentSaveReason.Manual if latestConfig().scalafmt.onSave =>
          scribe.info(s"Formatting on manual save: $params.textDocument")
          val uri = Uri(params.textDocument)
          documentFormattingProvider.format(uri.toInput(buffers))
        case _ =>
          Task.now { Right(List()) }
      }
    }
    .notification(td.didSave) { _ =>
      // if sbt is not connected or the command is empty it won't do anything
      sbtExec()
    }
    .notification(ws.didChangeConfiguration) { params =>
      params.settings.hcursor.downField("metals").as[Configuration] match {
        case Left(err) =>
          showMessage.notify(
            ShowMessageParams(MessageType.Error, err.toString)
          )
        case Right(conf) =>
          scribe.info(s"Configuration updated $conf")
          configurationSubscriber.onNext(conf)
      }
    }
    .notification(ws.didChangeWatchedFiles) { params =>
      params.changes.foreach {
        case FileEvent(
            Uri(path),
            FileChangeType.Created | FileChangeType.Changed
            ) =>
          onChangedFile(path.toAbsolutePath) {
            scribe.warn(s"Unknown file extension for path $path")
          }

        case event =>
          scribe.warn(s"Unhandled file event: $event")
          ()
      }
      ()
    }
    .request(td.definition) { params =>
      DefinitionProvider.definition(
        symbolIndex,
        Uri(params.textDocument.uri),
        params.position,
        tempSourcesDir
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
    .request(ws.symbol) { params =>
      symbolIndex.workspaceSymbols(params.query)
    }
    .request(td.documentHighlight) { params =>
      if (latestConfig().highlight.enabled) {
        DocumentHighlightProvider.highlight(
          symbolIndex,
          Uri(params.textDocument.uri),
          params.position
        )
      } else DocumentHighlightProvider.empty
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
    .request(td.rename) { params =>
      RenameProvider.rename(params, symbolIndex)
    }
    .requestAsync(ws.executeCommand) { params =>
      scribe.info(s"executeCommand $params")
      WorkspaceCommand.withNameOption(params.command) match {
        case None =>
          Task {
            val msg = s"Unknown command ${params.command}"
            scribe.error(msg)
            Left(Response.invalidParams(msg))
          }
        case Some(command) =>
          executeCommand(command, params)
      }
    }

  import WorkspaceCommand._
  val ok = Right(Json.Null)
  private def executeCommand(
      command: WorkspaceCommand,
      params: ExecuteCommandParams
  ): Task[Either[Response.Error, Json]] = command match {
    case ClearIndexCache =>
      Task {
        scribe.info("Clearing the index cache")
        MetalsServices.clearCacheDirectory()
        symbolIndex.clearIndex()
        Right(Json.Null)
      }
    case SbtConnect =>
      Task {
        SbtServer.readVersion(cwd) match {
          case Some(ver) if ver.startsWith("0.") || ver.startsWith("1.0") =>
            showMessage.warn(
              s"sbt v${ver} used in this project doesn't have server functionality. " +
                "Upgrade to sbt v1.1+ to enjoy Metals integration with the sbt server."
            )
          case _ =>
            connectToSbtServer()
        }
        Right(Json.Null)
      }
  }

  private def sbtExec(commands: String*): Task[Unit] = {
    val cmd = commands.mkString("; ", "; ", "")
    sbtServer match {
      case None =>
        scribe.warn(
          s"Trying to execute commands when there is no connected sbt server: ${cmd}"
        )
        Task.unit
      case Some(sbt) =>
        scribe.debug(s"sbt/exec: ${cmd}")
        Sbt
          .exec(cmd)(sbt.client)
          .onErrorRecover {
            case NonFatal(err) =>
              // TODO(olafur) figure out why this "broken pipe" is not getting
              // caught here.
              scribe.error("Failed to send sbt compile", err)
              showMessage.warn(
                "Lost connection to sbt server. " +
                  "Restart the sbt session and run the 'Re-connect to sbt server' command"
              )
          }
    }
  }
  private def sbtExec(): Task[Unit] = sbtExec(latestConfig().sbt.command)

  private val loadPluginJars: Coeval[List[AbsolutePath]] = Coeval.evalOnce {
    Jars.fetch("ch.epfl.scala", "load-plugin_2.12", "0.1.0+2-496ac670")
  }

  private def sbtExecWithMetalsPlugin(commands: String*): Task[Unit] = {
    val metalsPluginModule = ModuleID(
      "org.scalameta",
      "sbt-metals",
      scala.meta.metals.internal.BuildInfo.version
    )
    val metalsPluginRef = "scala.meta.sbt.MetalsPlugin"
    val loadPluginClasspath = loadPluginJars.value.mkString(File.pathSeparator)
    val loadCommands = Seq(
      s"apply -cp ${loadPluginClasspath} ch.epfl.scala.loadplugin.LoadPlugin",
      s"""if-absent ${metalsPluginRef} "load-plugin ${metalsPluginModule} ${metalsPluginRef}"""",
    )
    sbtExec(loadCommands ++ commands: _*)
  }

  private def connectToSbtServer(): Unit = {
    sbtServer.foreach(_.disconnect())
    val services = SbtServer.forwardingServices(client, latestConfig)
    SbtServer.connect(cwd, services)(s.sbt).foreach {
      case Left(err) => showMessage.error(err)
      case Right(server) =>
        val msg = "Established connection with sbt server 😎"
        scribe.info(msg)
        showMessage.info(msg)
        sbtServer = Some(server)
        cancelEffects ::= server.runningServer
        server.runningServer.onComplete {
          case Failure(err) =>
            scribe.error(s"Unexpected failure from sbt server connection", err)
            showMessage.error(err.getMessage)
          case Success(()) =>
            sbtServer = None
            showMessage.warn("Disconnected from sbt server")
        }
        sbtExecWithMetalsPlugin("semanticdbEnable").runAsync.foreach { _ =>
          scribe.info("semanticdb-scalac is enabled")
        }
        sbtExec().runAsync // run configured command right away
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

  private def onChangedFile(
      path: AbsolutePath
  )(fallback: => Unit): Unit = {
    scribe.info(s"File $path changed")
    path.toRelative(cwd) match {
      case Semanticdbs.File() =>
        fileSystemSemanticdbSubscriber.onNext(path)
      case CompilerConfig.File() =>
        compilerConfigSubscriber.onNext(path)
      case SbtServer.ActiveJson() =>
        connectToSbtServer()
      case _ =>
        fallback
    }
  }
}

object MetalsServices {
  lazy val cacheDirectory: AbsolutePath = {
    val path = AbsolutePath(
      ProjectDirectories.fromProjectName("metals").projectCacheDir
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

  def onError(e: Throwable): Unit = {
    scribe.error(e.getMessage, e)
  }
}
