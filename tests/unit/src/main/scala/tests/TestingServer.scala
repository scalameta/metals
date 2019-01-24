package tests

import com.google.gson.JsonParser
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util
import java.util.Collections
import java.util.concurrent.ScheduledExecutorService
import org.eclipse.lsp4j.ClientCapabilities
import scala.meta.internal.metals.PositionSyntax._
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.TextDocumentClientCapabilities
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceClientCapabilities
import org.eclipse.{lsp4j => l}
import org.scalactic.source.Position
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.meta.Input
import scala.meta.internal.io.FileIO
import scala.meta.internal.io.PathIO
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.Debug
import scala.meta.internal.metals.DidFocusResult
import scala.meta.internal.metals.Directories
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsLanguageServer
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.ProgressTicks
import scala.meta.internal.metals.Time
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.semanticdb.Scala.Symbols
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.{semanticdb => s}
import scala.meta.io.AbsolutePath
import scala.meta.io.RelativePath
import scala.{meta => m}
import tests.MetalsTestEnrichments._

/**
 * Wrapper around `MetalsLanguageServer` with helpers methods for testing purpopses.
 *
 * - manages text synchronization, example didSave writes file contents to disk.
 * - pretty-prints results of textDocument/definition for readable multiline string diffing.
 *
 * This class is great for testing because we get `Future[T]` values back from JSON-RPC
 * notifications like didOpen and didSave so that we can run callbacks once asynchronous background
 * jobs complete (example: BSP compilation, source indexing). It is not possible to test the
 * language server the same way from a real editor client like VS Code because JSON-RPC
 * notifications are `Any => Unit`, they cannot respond.
 */
final class TestingServer(
    workspace: AbsolutePath,
    client: TestingClient,
    buffers: Buffers,
    config: MetalsServerConfig,
    bspGlobalDirectories: List[AbsolutePath],
    sh: ScheduledExecutorService,
    time: Time
)(implicit ex: ExecutionContextExecutorService) {
  val server = new MetalsLanguageServer(
    ex,
    buffers = buffers,
    redirectSystemOut = false,
    config = config,
    progressTicks = ProgressTicks.none,
    bspGlobalDirectories = bspGlobalDirectories,
    sh = sh,
    time = time
  )
  server.connectToLanguageClient(client)
  private val readonlySources = TrieMap.empty[String, AbsolutePath]
  def statusBarHistory: String = {
    // collect both published items in the client and pending items from the server.
    val all = List(
      server.statusBar.pendingItems,
      client.statusParams.asScala.map(_.text)
    ).flatten
    all.distinct.mkString("\n")
  }

  private def write(layout: String): Unit = {
    FileLayout.fromString(layout, root = workspace)
  }

  def workspaceSymbol(query: String, includeKind: Boolean = false): String = {
    val infos = server.workspaceSymbol(query)
    infos
      .map { info =>
        val kind =
          if (includeKind) s" ${info.getKind}"
          else ""
        s"${info.getContainerName}${info.getName}$kind"
      }
      .mkString("\n")
  }
  def workspaceSources: Seq[AbsolutePath] = {
    for {
      sourceDirectory <- server.buildTargets.sourceDirectories.toSeq
      if sourceDirectory.isDirectory
      source <- FileIO.listAllFilesRecursively(sourceDirectory)
    } yield source
  }

  def assertReferenceDefinitionBijection(): Unit = {
    val compare = workspaceReferences()
    assert(compare.definition.nonEmpty)
    assert(compare.references.nonEmpty)
    DiffAssertions.assertNoDiff(
      compare.referencesFormat,
      compare.definitionFormat,
      "references",
      "definition"
    )
  }
  def assertReferenceDefinitionDiff(
      expectedDiff: String
  )(implicit pos: Position): Unit = {
    DiffAssertions.assertNoDiffOrPrintObtained(
      workspaceReferences().diff,
      expectedDiff,
      "references",
      "definition"
    )
  }
  def workspaceReferences(): WorkspaceSymbolReferences = {
    val inverse =
      mutable.Map.empty[SymbolReference, mutable.ListBuffer[Location]]
    val inputsCache = mutable.Map.empty[String, Input]
    def readInput(uri: String): Input = {
      inputsCache.getOrElseUpdate(
        uri, {
          val path = uri.toAbsolutePath
          path
            .toInputFromBuffers(buffers)
            .copy(path = path.toRelative(workspace).toURI(false).toString)
        }
      )
    }
    def newRef(symbol: String, loc: Location): SymbolReference =
      SymbolReference(symbol, loc, loc.getRange.toMeta(readInput(loc.getUri)))
    for {
      source <- workspaceSources
      input = source.toInputFromBuffers(buffers)
      identifier = source.toTextDocumentIdentifier
      token <- input.tokenize.get
      if token.isIdentifier
      params = token.toPositionParams(identifier)
      definition = server.definitionResult(params)
      if !definition.symbol.isPackage
      if !definition.definition.exists(_.isDependencySource(workspace))
      location <- definition.locations.asScala
    } {
      val buf = inverse.getOrElseUpdate(
        newRef(definition.symbol, location),
        mutable.ListBuffer.empty
      )
      buf += new Location(source.toURI.toString, token.pos.toLSP)
    }
    val definition = Seq.newBuilder[SymbolReference]
    val references = Seq.newBuilder[SymbolReference]
    for {
      (ref, expectedLocations) <- inverse.toSeq.sortBy(_._1.symbol)
    } {
      val params = new ReferenceParams(new ReferenceContext(true))
      params.setPosition(ref.location.getRange.getStart)
      params.setTextDocument(new TextDocumentIdentifier(ref.location.getUri))
      val obtainedLocations = server.referencesResult(params)
      references ++= obtainedLocations.locations.map(
        l => newRef(obtainedLocations.symbol, l)
      )
      definition ++= expectedLocations.map(
        l => newRef(ref.symbol, l)
      )
    }
    WorkspaceSymbolReferences(references.result(), definition.result())
  }

  def initialize(
      layout: String,
      expectError: Boolean = false,
      preInitialized: () => Future[Unit] = () => Future.successful(())
  ): Future[Unit] = {
    Debug.printEnclosing()
    write(layout)
    QuickBuild.bloopInstall(workspace)
    val params = new InitializeParams
    val workspaceCapabilities = new WorkspaceClientCapabilities()
    val textDocumentCapabilities = new TextDocumentClientCapabilities
    params.setCapabilities(
      new ClientCapabilities(
        workspaceCapabilities,
        textDocumentCapabilities,
        null
      )
    )
    params.setRootUri(workspace.toURI.toString)
    for {
      _ <- server.initialize(params).asScala
      _ <- preInitialized()
      _ <- server.initialized(new InitializedParams).asScala
    } yield {
      if (!expectError) {
        assertBuildServerConnection()
      }
    }
  }

  def assertBuildServerConnection(): Unit = {
    require(server.buildServer.isDefined, "Build server did not initialize")
  }

  private def toPath(filename: String): AbsolutePath =
    TestingServer.toPath(workspace, filename)

  def executeCommand(command: String): Future[Unit] = {
    Debug.printEnclosing()
    server
      .executeCommand(
        new ExecuteCommandParams(command, Collections.emptyList())
      )
      .asScala
      .ignoreValue
  }
  def didFocus(filename: String): Future[DidFocusResult.Value] = {
    server.didFocus(toPath(filename).toURI.toString).asScala
  }
  def didSave(filename: String)(fn: String => String): Future[Unit] = {
    Debug.printEnclosing()
    val abspath = toPath(filename)
    val oldText = abspath.toInputFromBuffers(buffers).text
    val newText = fn(oldText)
    Files.write(
      abspath.toNIO,
      newText.getBytes(StandardCharsets.UTF_8)
    )
    server
      .didSave(
        new DidSaveTextDocumentParams(
          new TextDocumentIdentifier(abspath.toURI.toString)
        )
      )
      .asScala
  }

  def didChange(filename: String)(fn: String => String): Future[Unit] = {
    Debug.printEnclosing()
    val abspath = toPath(filename)
    val oldText = abspath.toInputFromBuffers(buffers).text
    val newText = fn(oldText)
    server
      .didChange(
        new DidChangeTextDocumentParams(
          new VersionedTextDocumentIdentifier(abspath.toURI.toString, 0),
          Collections.singletonList(new TextDocumentContentChangeEvent(newText))
        )
      )
      .asScala
  }

  def didOpen(filename: String): Future[Unit] = {
    Debug.printEnclosing()
    val abspath = toPath(filename)
    val uri = abspath.toURI.toString
    val extension = PathIO.extension(abspath.toNIO)
    val text = abspath.readText
    server
      .didOpen(
        new DidOpenTextDocumentParams(
          new TextDocumentItem(uri, extension, 0, text)
        )
      )
      .asScala
  }

  def didClose(filename: String): Future[Unit] = {
    Debug.printEnclosing()
    val abspath = toPath(filename)
    val uri = abspath.toURI.toString
    Future.successful {
      server
        .didClose(
          new DidCloseTextDocumentParams(
            new TextDocumentIdentifier(uri)
          )
        )
    }
  }

  def didChangeConfiguration(config: String): Future[Unit] = {
    Future {
      val wrapped = UserConfiguration.toWrappedJson(config)
      val json = new JsonParser().parse(wrapped)
      server.didChangeConfiguration(new DidChangeConfigurationParams(json))
    }
  }

  def references(
      filename: String,
      substring: String
  ): Future[String] = {
    val path = toPath(filename)
    val input = path.toInputFromBuffers(buffers)
    val index = input.text.lastIndexOf(substring)
    if (index < 0) {
      throw new IllegalArgumentException(
        s"the string '$substring' is not a substring of text '${input.text}'"
      )
    }
    val params = new ReferenceParams(new ReferenceContext(true))
    params.setTextDocument(path.toTextDocumentIdentifier)
    val offset = index + substring.length - 1
    val pos = m.Position.Range(input, offset, offset + 1)
    params.setPosition(new l.Position(pos.startLine, pos.startColumn))
    server.references(params).asScala.map { r =>
      r.asScala
        .map { l =>
          val path = l.getUri.toAbsolutePath
          val input = path
            .toInputFromBuffers(buffers)
            .copy(path = path.toRelative(workspace).toURI(false).toString)
          val pos = l.getRange.toMeta(input)
          pos.formatMessage("info", "reference")
        }
        .mkString("\n")
    }
  }
  def formatting(filename: String): Future[Unit] = {
    val path = toPath(filename)
    server
      .formatting(
        new DocumentFormattingParams(
          new TextDocumentIdentifier(path.toURI.toString),
          new FormattingOptions
        )
      )
      .asScala
      .map(textEdits => applyTextEdits(path, textEdits))
  }

  private def applyTextEdits(
      path: AbsolutePath,
      textEdits: util.List[TextEdit]
  ): Unit = {
    for {
      buffer <- buffers.get(path)
    } yield {
      val input = Input.String(buffer)
      val newBuffer = textEdits.asScala.foldLeft(buffer) {
        case (buf, edit) =>
          val startPosition = edit.getRange.getStart
          val endPosition = edit.getRange.getEnd
          val startOffset =
            input.toOffset(startPosition.getLine, startPosition.getCharacter)
          val endOffset =
            input.toOffset(endPosition.getLine, endPosition.getCharacter)
          buf.patch(startOffset, edit.getNewText, endOffset)
      }
      buffers.put(path, newBuffer)
    }
  }

  private def toSemanticdbTextDocument(path: AbsolutePath): s.TextDocument = {
    val input = path.toInputFromBuffers(buffers)
    val identifier = path.toTextDocumentIdentifier
    val occurrences = ListBuffer.empty[s.SymbolOccurrence]
    input.tokenize.get.foreach { token =>
      val params = token.toPositionParams(identifier)
      val definition = server.definitionResult(params)
      definition.definition.foreach { path =>
        if (path.isDependencySource(workspace)) {
          readonlySources(path.toNIO.getFileName.toString) = path
        }
      }
      val locations = definition.locations.asScala.toList
      val symbols = locations.map { location =>
        val isSameFile = identifier.getUri == location.getUri
        if (isSameFile) {
          s"L${location.getRange.getStart.getLine}"
        } else {
          val path = location.getUri.toAbsolutePath
          val filename = path.toNIO.getFileName
          if (path.isDependencySource(workspace)) filename.toString
          else s"$filename:${location.getRange.getStart.getLine}"
        }
      }
      val occurrence = if (token.isIdentifier) {
        if (definition.symbol.isPackage) None // ignore packages
        else if (symbols.isEmpty) Some("<no symbol>")
        else Some(Symbols.Multi(symbols))
      } else {
        if (symbols.isEmpty) None // OK, expected
        else Some(s"unexpected: ${Symbols.Multi(symbols)}")
      }
      occurrences ++= occurrence.map { symbol =>
        s.SymbolOccurrence(Some(token.pos.toSemanticdb), symbol)
      }
    }
    s.TextDocument(
      schema = s.Schema.SEMANTICDB4,
      uri = input.path,
      text = input.text,
      occurrences = occurrences
    )
  }

  val Docstring = " *\\/?\\*.*".r
  def workspaceDefinitions: String = {
    buffers.open.toSeq
      .sortBy(_.toURI.toString)
      .map { path =>
        val textDocument = toSemanticdbTextDocument(path)
        val relpath =
          path.toRelative(workspace).toURI(isDirectory = false).toString
        val printedTextDocument = Semanticdbs.printTextDocument(textDocument)
        s"/$relpath\n$printedTextDocument"
      }
      .mkString("\n")
  }

  def documentSymbols(uri: String): String = {
    val path = toPath(uri)
    val input = path.toInputFromBuffers(buffers)
    val identifier = path.toTextDocumentIdentifier
    val params = new DocumentSymbolParams(identifier)
    val documentSymbols = server.documentSymbolResult(params).asScala
    val symbols = documentSymbols.toSymbolInformation(uri)
    val textDocument = s.TextDocument(
      schema = s.Schema.SEMANTICDB4,
      language = s.Language.SCALA,
      text = input.text,
      occurrences = symbols.map(_.toSymbolOccurrence)
    )
    Semanticdbs.printTextDocument(textDocument)
  }

  def textContents(filename: String): String =
    toPath(filename).toInputFromBuffers(buffers).text
  def bufferContent(filename: String): String =
    buffers
      .get(toPath(filename))
      .getOrElse(throw new NoSuchElementException(filename))

  def cleanUnmanagedFiles(): Unit = {
    Files.walkFileTree(
      workspace.toNIO,
      new SimpleFileVisitor[Path] {
        override def visitFile(
            file: Path,
            attrs: BasicFileAttributes
        ): FileVisitResult = {
          PathIO.extension(file) match {
            case "json" if file.getParent.endsWith(".bloop") =>
            case _ =>
              Files.delete(file)
          }
          super.visitFile(file, attrs)
        }
        override def postVisitDirectory(
            dir: Path,
            exc: IOException
        ): FileVisitResult = {
          val isEmpty = !Files.list(dir).iterator().hasNext
          if (isEmpty) {
            Files.delete(dir)
          }
          super.postVisitDirectory(dir, exc)
        }
        override def preVisitDirectory(
            dir: Path,
            attrs: BasicFileAttributes
        ): FileVisitResult = {
          if (dir.endsWith(".metals"))
            FileVisitResult.SKIP_SUBTREE
          else super.preVisitDirectory(dir, attrs)
        }
      }
    )
  }
}

object TestingServer {
  def toPath(workspace: AbsolutePath, filename: String): AbsolutePath = {
    val path = RelativePath(filename)
    List(
      workspace,
      workspace.resolve(Directories.readonly)
    ).map(_.resolve(path))
      .find(_.isFile)
      .getOrElse {
        throw new IllegalArgumentException(s"no such file: $filename")
      }
  }
}
