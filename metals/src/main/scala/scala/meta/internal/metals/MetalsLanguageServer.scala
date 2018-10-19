package scala.meta.internal.metals

import ch.epfl.scala.bsp4j.BuildTargetTextDocumentsParams
import ch.epfl.scala.bsp4j.DependencySourcesParams
import ch.epfl.scala.bsp4j.ScalacOptionsParams
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
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
import scala.meta.internal.inputs._
import scala.meta.internal.io.FileIO
import scala.meta.internal.io.PathIO
import scala.meta.internal.metals.ProtocolConverters._
import scala.meta.internal.mtags.Enrichments._
import scala.meta.internal.mtags.FingerprintProvider
import scala.meta.internal.mtags.MD5
import scala.meta.internal.mtags.Mtags
import scala.meta.internal.mtags.OnDemandSymbolIndex
import scala.meta.internal.mtags.SemanticdbClasspath
import scala.meta.internal.mtags.Symbol
import scala.meta.internal.mtags.TextDocumentLookup
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.io.AbsolutePath
import scala.util.control.NonFatal

case class Fingerprint(text: String, md5: String)

case class Buffers(map: TrieMap[AbsolutePath, String] = TrieMap.empty) {
  def put(key: AbsolutePath, value: String): Unit = map.put(key, value)
  def get(key: AbsolutePath): Option[String] = map.get(key)
  def remove(key: AbsolutePath): Unit = map.remove(key)
}

class MetalsLanguageServer(ec: ExecutionContext) {
  private implicit val executionContext = ec

  val buffers = Buffers()
  private val fingerprints =
    TrieMap.empty[AbsolutePath, ConcurrentLinkedQueue[Fingerprint]]
  private val fingerprintProvider = new FingerprintProvider {
    override def lookup(path: AbsolutePath, md5: String): Option[String] = {
      fingerprints.get(path).flatMap { prints =>
        prints.asScala.find(_.md5 == md5).map(_.text)
      }
    }
  }

  private val mtags = new Mtags
  private var workspace = PathIO.workingDirectory
  private var semanticdbs: SemanticdbClasspath =
    SemanticdbClasspath(workspace, fingerprints = fingerprintProvider)
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

  def checksumBuildSources(): Unit = {
    SbtChecksum.digest(workspace).foreach { checksum =>
      val out = workspace.resolve(".metals").resolve("sbt.md5")
      val shouldReimport =
        !out.isFile || {
          val old = FileIO.slurp(out, StandardCharsets.UTF_8)
          old != checksum
        }
      if (shouldReimport) {
        val params = new ShowMessageRequestParams()
        params.setMessage("Would you like to import the project?")
        params.setType(MessageType.Info)
        params.setActions(
          List(
            new MessageActionItem("Yes")
          ).asJava
        )
        val response = languageClient.showMessageRequest(params).get()
        if (response != null) {
          cancelables.add(
            BloopInstall.run(workspace, languageClient, executionContext)
          )
        }
      }
    }
  }
  def indexSourcesInProject(): Unit = Future {
    // Visit every file and directory in the workspace and register:
    // 1. scala/java source files, index their toplevel definitions.
    // 2. class directories that may contain SemanticDB files.
    Files.walkFileTree(
      workspace.toNIO,
      new SimpleFileVisitor[Path] {
        override def visitFile(
            file: Path,
            attrs: BasicFileAttributes
        ): FileVisitResult = {
          val path = AbsolutePath(file)
          if (path.isScalaOrJava) {
            index.addSourceFile(path, None)
          }
          super.visitFile(file, attrs)
        }
        override def preVisitDirectory(
            dir: Path,
            attrs: BasicFileAttributes
        ): FileVisitResult = {
          val path = AbsolutePath(dir)
          if (path.resolve("META-INF").isDirectory) {
            semanticdbs.loader.addEntry(path)
            FileVisitResult.SKIP_SUBTREE
          } else {
            super.preVisitDirectory(dir, attrs)
          }
        }
      }
    )
  }

  def indexWorkspace(): Unit = bsp.foreach { build =>
    for {
      buildTargets <- build.server.workspaceBuildTargets().toScala
      ids = buildTargets.getTargets.map(_.getId)
      scalacOptions <- build.server
        .buildTargetScalacOptions(new ScalacOptionsParams(ids))
        .toScala
    } {
      scalacOptions.getItems.asScala.foreach { item =>
        semanticdbs.loader.addEntry(item.getClassDirectory.toAbsolutePath)
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
      checksumBuildSources()
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
  def textDocumentDidSave(params: DidSaveTextDocumentParams): Unit = {
    bsp.foreach { build =>
      val ids = build.allWorkspaceIds()
      for {
        sources <- build.server
          .buildTargetTextDocuments(new BuildTargetTextDocumentsParams(ids))
          .toScala
      } {
        sources.getTextDocuments
      }
    }
  }

  @JsonNotification("workspace/didChangeConfiguration")
  def workspaceDidChangeConfiguration(
      params: DidChangeConfigurationParams
  ): Unit = {
    params.getSettings
  }

  @JsonNotification("workspace/didChangeWatchedFiles")
  def workspaceDidChangeWatchedFiles(
      params: DidChangeWatchedFilesParams
  ): Unit = {
    scribe.info(s"didChangeWatchedFiles: $params")
    params.getChanges.forEach { change =>
      val path = change.getUri.toAbsolutePath
      val project = workspace.resolve("project").toNIO
      val parent = path.toNIO.getParent
      val isBuildFile = parent == workspace.toNIO || parent == project
      val isSbtOrScala = path.isSbtOrScala
      scribe.info(path.toString())
      scribe.info(isBuildFile.toString)
      scribe.info(isSbtOrScala.toString)
      if (isBuildFile && isSbtOrScala) {
        checksumBuildSources()
      }
    }
  }

  val sbtOrScala = FileSystems.getDefault.getPathMatcher("glob:*.{sbt,scala}")

  @JsonRequest("textDocument/definition")
  def textDocumentDefinition(
      position: TextDocumentPositionParams
  ): CompletableFuture[util.List[Location]] =
    CompletableFutures.computeAsync { token =>
      val path = position.getTextDocument.getUri.toAbsolutePath
      semanticdbs.textDocument(path).toOption match {
        case Some(doc) =>
          val bufferInput = path.toInputFromBuffers(buffers)
          val editDistance = TokenEditDistance(doc.toInput, bufferInput)
          val originalPosition = editDistance.toOriginal(
            position.getPosition.getLine,
            position.getPosition.getCharacter
          )
          val queryPosition0 = originalPosition.foldResult(
            onPosition = pos => {
              position.getPosition.setLine(pos.startLine)
              position.getPosition.setCharacter(pos.startColumn)
              Some(position.getPosition)
            },
            onUnchanged = () => Some(position.getPosition),
            onNoMatch = () => None
          )
          val result = for {
            queryPosition <- queryPosition0
          } yield {
            val location: Option[Location] = doc.occurrences
              .find(_.encloses(queryPosition))
              .flatMap { occ =>
                val isLocal =
                  occ.symbol.isLocal ||
                    doc.occurrences.exists { localOccurrence =>
                      localOccurrence.role.isDefinition &&
                      localOccurrence.symbol == occ.symbol
                    }
                val ddoc
                  : Option[(TextDocument, TokenEditDistance, String, String)] =
                  if (isLocal) {
                    Some(
                      (
                        doc,
                        editDistance,
                        occ.symbol,
                        position.getTextDocument.getUri
                      )
                    )
                  } else {
                    for {
                      defn <- index.definition(Symbol(occ.symbol))
                      defnDoc = semanticdbs.textDocument(defn.path) match {
                        case TextDocumentLookup.Success(d) => d
                        case TextDocumentLookup.Stale(_, _, d) => d
                        case _ =>
                          // read file from disk instead of buffers because text on disk is more
                          // likely to parse successfully.
                          val defnRevisedInput = defn.path.toInput
                          mtags.index(defn.path.toLanguage, defnRevisedInput)
                      }
                      defnOriginalInput = defnDoc.toInput
                      defnUri = defn.path.toDiskURI(workspace)
                      defnEditDistance = TokenEditDistance(
                        defnOriginalInput,
                        defn.path.toInputFromBuffers(buffers)
                      )
                    } yield
                      (
                        defnDoc,
                        defnEditDistance,
                        defn.definitionSymbol.value,
                        defnUri.toString
                      )
                  }
                for {
                  (defnDoc, distance, symbol, uri) <- ddoc
                  location <- defnDoc.definition(uri, symbol)
                  revisedPosition = distance.toRevised(
                    location.getRange.getStart.getLine,
                    location.getRange.getStart.getCharacter
                  )
                  result <- revisedPosition.foldResult(
                    pos => {
                      val start = location.getRange.getStart
                      start.setLine(pos.startLine)
                      start.setCharacter(pos.startColumn)
                      val end = location.getRange.getEnd
                      end.setLine(pos.endLine)
                      end.setCharacter(pos.endColumn)
                      Some(location)
                    },
                    () => Some(location),
                    () => None
                  )
                } yield result
              }
            location
          }
          result match {
            case Some(location) => location.toList.asJava
            case None => Collections.emptyList()
          }
        case None =>
          scribe.info(s"no hit for $position")
          Collections.emptyList()
      }
    }

}
