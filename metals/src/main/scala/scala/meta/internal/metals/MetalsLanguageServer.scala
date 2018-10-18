package scala.meta.internal.metals

import ch.epfl.scala.bsp4j.DependencySourcesParams
import ch.epfl.scala.bsp4j.ScalacOptionsParams
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.util
import java.util.Collections
import java.util.concurrent.CompletableFuture
import org.eclipse.lsp4j._
import org.eclipse.lsp4j.jsonrpc.CompletableFutures
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.services._
import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import java.util.concurrent.ConcurrentLinkedQueue
import scala.compat.java8.FutureConverters._
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.meta.internal.io.FileIO
import scala.meta.internal.io.PathIO
import scala.meta.internal.metals.ProtocolConverters._
import scala.meta.internal.mtags.Enrichments._
import scala.meta.internal.mtags.MD5
import scala.meta.internal.mtags.Mtags
import scala.meta.internal.mtags.OnDemandSymbolIndex
import scala.meta.internal.mtags.SemanticdbClasspath
import scala.meta.internal.mtags.Symbol
import scala.meta.internal.semanticdb.Scala._
import scala.meta.io.AbsolutePath
import scala.util.control.NonFatal

case class Fingerprint(text: String, md5: String)

class MetalsLanguageServer(ec: ExecutionContext) {
  implicit val executionContext = ec

  implicit val buffers = TrieMap.empty[AbsolutePath, String]
  implicit val fingerprints =
    TrieMap.empty[AbsolutePath, ConcurrentLinkedQueue[Fingerprint]]

  private val mtags = new Mtags
  private var workspace = PathIO.workingDirectory
  private var semanticdbs: SemanticdbClasspath = SemanticdbClasspath(workspace)
  private var index = OnDemandSymbolIndex()
  private def updateWorkspaceDirectory(params: InitializeParams): Unit = {
    workspace = AbsolutePath(Paths.get(URI.create(params.getRootUri)))
    semanticdbs = semanticdbs.copy(sourceroot = workspace)
    MetalsLogger.setupLspLogger(workspace)
  }

  private var languageClient: LanguageClient = _
  def connect(client: LanguageClient): Unit = {
    this.languageClient = client
    LanguageClientLogger.languageClient = Some(client)
  }

  private val cancelables = new OpenCancelable

  private var bsp = Option.empty[BuildServerConnection]
  private def connectToBuildServer(): Unit = {
    try {
      val buildClient = new MetalsBuildClient(languageClient)
      val connection =
        BuildServerConnection.connect(workspace, languageClient, buildClient)
      connection.foreach(c => cancelables.addAll(c.cancelables))
      bsp = connection
      bsp.foreach { build =>
        build.initialize(workspace)
        languageClient.showMessage(
          new MessageParams(
            MessageType.Info,
            "Established connection with bloop, importing project... (this may take a while)"
          )
        )
        indexWorkspace()
      }
    } catch {
      case NonFatal(e) =>
        scribe.error("Unable to connect to build server", e)
    }
  }

  def indexSourcesInProject(): Unit = Future {
    FileIO
      .listAllFilesRecursively(workspace)
      .iterator
      .filter(_.isScalaOrJava)
      .foreach { file =>
        index.addSourceFile(file, None)
      }
  }

  def indexWorkspace(): Unit = bsp.foreach { build =>
    index = OnDemandSymbolIndex()
    for {
      buildTargets <- build.server.workspaceBuildTargets().toScala
      ids = buildTargets.getTargets.map(_.getId)
      scalacOptions <- build.server
        .buildTargetScalacOptions(new ScalacOptionsParams(ids))
        .toScala
    } {
      scalacOptions.getItems.asScala.foreach { item =>
        semanticdbs.loader.addEntry(AbsolutePath(item.getClassDirectory))
      }
      for {
        sources <- build.server
          .buildTargetDependencySources(new DependencySourcesParams(ids))
          .toScala
      } {
        sources.getItems.asScala.foreach { item =>
          Option(item.getSources).toList
            .flatMap(_.asScala)
            .foreach { soureUri =>
              scribe.info(soureUri)
              index.addSourceJar(AbsolutePath(Paths.get(URI.create(soureUri))))
            }
        }
      }
    }
  }

  @JsonRequest("initialize")
  def initialize(
      params: InitializeParams
  ): CompletableFuture[InitializeResult] = {
    updateWorkspaceDirectory(params)
    val capabilities = new ServerCapabilities()
    capabilities.setDefinitionProvider(true)
    capabilities.setTextDocumentSync(TextDocumentSyncKind.Full)
    CompletableFuture.completedFuture(new InitializeResult(capabilities))
  }

  @JsonNotification("initialized")
  def initialized(params: InitializedParams): Unit =
    Future {
      indexSourcesInProject()
      connectToBuildServer()
    }(ec)

  @JsonRequest("shutdown")
  def shutdown(): CompletableFuture[Object] = {
    cancelables.cancel()
    CompletableFuture.completedFuture(null)
  }
  @JsonNotification("exit")
  def exit(): Unit = {
    sys.exit(0)
  }

  @JsonNotification("textDocument/didOpen")
  def textDocumentDidOpen(params: DidOpenTextDocumentParams): Unit = {
    val path = params.getTextDocument.getUri.toAbsolutePath
    val text = FileIO.slurp(path, StandardCharsets.UTF_8)
    val md5 = MD5.compute(text)
    val value = fingerprints.getOrElseUpdate(path, new ConcurrentLinkedQueue())
    value.add(Fingerprint(text, md5))
  }

  @JsonNotification("textDocument/didChange")
  def textDocumentDidChange(params: DidChangeTextDocumentParams): Unit = {
    params.getContentChanges.asScala.headOption.foreach { change =>
      buffers.put(params.getTextDocument.getUri.toAbsolutePath, change.getText)
    }
  }
  @JsonNotification("textDocument/didClose")
  def textDocumentDidClose(params: DidCloseTextDocumentParams): Unit = {
    buffers.remove(params.getTextDocument.getUri.toAbsolutePath)
  }
  @JsonNotification("textDocument/didSave")
  def textDocumentDidSave(params: DidSaveTextDocumentParams): Unit = {}

  @JsonNotification("workspace/didChangeConfiguration")
  def workspaceDidChangeConfiguration(
      params: DidChangeConfigurationParams
  ): Unit = {
    params.getSettings
  }

  @JsonNotification("workspace/didChangeWatchedFiles")
  def workspaceDidChangeWatchedFiles(
      params: DidChangeWatchedFilesParams
  ): Unit = ()

  @JsonRequest("textDocument/definition")
  def textDocumentDefinition(
      position: TextDocumentPositionParams
  ): CompletableFuture[util.List[Location]] =
    CompletableFutures.computeAsync { token =>
      val path = position.getTextDocument.getUri.toAbsolutePath
      pprint.log(semanticdbs.loader.loader.getURLs)
      semanticdbs.textDocument(path).toOption match {
        case Some(doc) =>
          val uri = position.getTextDocument.getUri
          val location: Option[Location] = doc.occurrences
            .find(_.encloses(position.getPosition))
            .flatMap { occ =>
              scribe.info(s"occurrence: ${occ.symbol}")
              if (occ.symbol.isLocal) {
                doc.definition(uri, occ.symbol)
              } else {
                for {
                  defn <- index.definition(Symbol(occ.symbol))
                  _ = scribe.info(s"defn: ${defn.path}")
                  input = defn.path.toInput
                  defnDoc = mtags.index(defn.path.toLanguage, input)
                  location <- defnDoc.definition(uri, occ.symbol)
                } yield location
              }
            }
          location.toList.asJava
        case None =>
          scribe.info(s"no hit for $position")
          Collections.emptyList()
      }
    }

}
