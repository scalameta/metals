package scala.meta.languageserver

import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.nio.file.Files
import scala.collection.mutable.ListBuffer
import scala.util.control.NonFatal
import com.typesafe.scalalogging.LazyLogging
import langserver.core.LanguageServer
import langserver.messages.ClientCapabilities
import langserver.messages.CompletionList
import langserver.messages.CompletionOptions
import langserver.messages.DefinitionResult
import langserver.messages.FileChangeType
import langserver.messages.FileEvent
import langserver.messages.Hover
import langserver.messages.MessageType
import langserver.messages.ResultResponse
import langserver.messages.ServerCapabilities
import langserver.messages.ShutdownResult
import langserver.types._
import monix.execution.Cancelable
import monix.execution.Scheduler
import monix.reactive.MulticastStrategy
import monix.reactive.Observable
import monix.reactive.Observer
import org.langmeta.internal.io.PathIO
import org.langmeta.internal.semanticdb.schema.Database
import org.langmeta.io.AbsolutePath

case class ServerConfig(
    cwd: AbsolutePath,
    setupScalafmt: Boolean = true,
    indexJDK: Boolean = true,
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
  val (semanticdbSubscriber, semanticdbPublisher) =
    ScalametaLanguageServer.semanticdbStream(cwd)
  val (compilerConfigSubscriber, compilerConfigPublisher) =
    Observable.multicast[AbsolutePath](MulticastStrategy.Publish)
  def onError(e: Throwable): Unit = {
    logger.error(e.getMessage, e)
  }
  val buffers: Buffers = Buffers()
  val compiler = new Compiler(
    config,
    stdout,
    compilerConfigPublisher,
    connection,
    buffers
  )
  val databasePublisher: Observable[Database] = Observable.merge(
    semanticdbPublisher.doOnError(onError),
    compiler.documentPublisher.map(doc => Database(doc :: Nil))
  )
  val symbolIndexer: SymbolIndexer = SymbolIndexer(
    cwd,
    connection,
    buffers
  )
  val onIndexDatabase: Observable[Effects.IndexSemanticdb] =
    databasePublisher.map { db =>
      symbolIndexer.indexDatabase(db)
      Effects.IndexSemanticdb
    }
  val scalafix: Linter = new Linter(
    cwd,
    stdout,
    connection,
    semanticdbPublisher.map(_.toDb(None)).doOnError(onError)
  )
  val scalafmt: Formatter =
    if (config.setupScalafmt) Formatter.classloadScalafmt("1.3.0")
    else Formatter.noop

  // TODO(olafur) more holistic error handling story.
  private def unsafe(thunk: => Unit): Unit =
    try thunk
    catch { case NonFatal(e) => logger.error(e.getMessage, e) }

  private def unsafeSeq[T](thunk: => Seq[T]): Seq[T] =
    try thunk
    catch { case NonFatal(e) => logger.error(e.getMessage, e); Nil }

  private val toCancel = ListBuffer.empty[Cancelable]

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
    toCancel += scalafix.linter.subscribe()
    toCancel += onIndexDatabase.subscribe()
    toCancel += compiler.onNewCompilerConfig.subscribe()
    loadAllRelevantFilesInThisWorkspace()
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
    val name = PathIO.extension(path.toNIO)
    logger.info(s"File $path changed, extension=$name")
    name match {
      case "semanticdb" => semanticdbSubscriber.onNext(path)
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
          scalafmt.format(contents, path.toString(), config)
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
    import scala.meta._

    // For a given node returns its closest ancestor which is a definition, declaration or a package object
    // NOTE: package is not considered a wrapping definition, but it could be (a subject to discuss)
    def wrappingDefinition(t: Tree): Option[Tree] = {
      if (
        t.is[Defn] ||
        t.is[Decl] ||
        t.is[Pkg.Object]
      ) Some(t)
      else t.parent.flatMap(wrappingDefinition)
    }

    def parentMember(t: Tree): Option[Tree] = {
      if (t.is[Member.Term] || t.is[Member.Type]) Some(t)
      else t.parent.flatMap(parentMember)
    }

    // This is needed only to unfold full package names
    def qualifiedName(t: Tree): Option[String] = t match {
      case Term.Name(name) =>
        Some(name)
      case Term.Select(qual, name) =>
        qualifiedName(qual).map { prefix => s"${prefix}.${name}" }
      case Pkg(sel: Term.Select, _) =>
        qualifiedName(sel)
      case m: Member =>
        Some(m.name.value)
      case _ => None
    }

    val path = Uri.toPath(td.uri).get
    val contents = buffers.read(path)
    for {
      tree <- contents.parse[Source].toOption.toList
      node <- tree.collect {
        case n if n.is[Member.Type] || n.is[Member.Term] => n
      }
      name <- qualifiedName(node)
      // Package as a wrapping definition for itself:
      defn <- if (node.is[Pkg]) Some(node) else wrappingDefinition(node)
    } yield SymbolInformation(
      name,
      defn.symbolKind,
      path.toLocation(defn.pos),
      defn.parent
        .flatMap(parentMember)
        .flatMap(wrappingDefinition)
        .flatMap(qualifiedName)
    )
  }

  override def gotoDefinitionRequest(
      td: TextDocumentIdentifier,
      position: Position
  ): DefinitionResult = {
    val path = Uri.toPath(td.uri).get
    symbolIndexer
      .goToDefinition(path, position.line, position.character)
      .getOrElse(DefinitionResult(Nil))
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
    val path = Uri.toPath(td.uri).get
    compiler.typeAt(path, position.line, position.character) match {
      case None => Hover(Nil, None)
      case Some(tpeName) =>
        Hover(
          contents = List(
            RawMarkedString(language = "scala", value = tpeName)
          ),
          range = None
        )
    }
  }

}

object ScalametaLanguageServer extends LazyLogging {
  def semanticdbStream(cwd: AbsolutePath)(
      implicit scheduler: Scheduler
  ): (Observer.Sync[AbsolutePath], Observable[Database]) = {
    val (subscriber, publisher) =
      Observable.multicast[AbsolutePath](MulticastStrategy.Publish)
    val semanticdbPublisher = publisher
      .map(path => Semanticdbs.loadFromFile(semanticdbPath = path, cwd))
    subscriber -> semanticdbPublisher
  }
}
