package tests

import com.google.gson.JsonParser
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.Collections
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.TextDocumentClientCapabilities
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.TextDocumentPositionParams
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceClientCapabilities
import scala.collection.concurrent.TrieMap
import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.meta.internal.io.PathIO
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.Debug
import scala.meta.internal.metals.Directories
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsLanguageClient
import scala.meta.internal.metals.MetalsLanguageServer
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.ProgressTicks
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.semanticdb.Scala.Symbols
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.{semanticdb => s}
import scala.meta.io.AbsolutePath
import scala.meta.io.RelativePath
import scala.meta.tokens.Token
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
    client: MetalsLanguageClient,
    buffers: Buffers,
    config: MetalsServerConfig,
    bspGlobalDirectories: List[AbsolutePath]
)(implicit ex: ExecutionContextExecutorService) {
  val server = new MetalsLanguageServer(
    ex,
    buffers = buffers,
    redirectSystemOut = false,
    config = config,
    progressTicks = ProgressTicks.none,
    bspGlobalDirectories = bspGlobalDirectories
  )
  server.connectToLanguageClient(client)
  private val readonlySources = TrieMap.empty[String, AbsolutePath]

  private def write(layout: String): Unit = {
    FileLayout.fromString(layout, root = workspace)
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
  def didFocus(filename: String): Future[Unit] = {
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

  private def toSemanticdbTextDocument(path: AbsolutePath): s.TextDocument = {
    val input = path.toInputFromBuffers(buffers)
    val identifier = path.toTextDocumentIdentifier
    val occurrences = ListBuffer.empty[s.SymbolOccurrence]
    input.tokenize.get.foreach { token =>
      val range = token.pos.toLSP
      val start = range.getStart
      val params = new TextDocumentPositionParams(identifier, start)
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
      val occurrence = token match {
        case _: Token.Ident | _: Token.Interpolation.Id =>
          if (definition.symbol.isPackage) None // ignore packages
          else if (symbols.isEmpty) Some("<no symbol>")
          else Some(Symbols.Multi(symbols))
        case _ =>
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

  def cancel(): Unit = {
    server.cancel()
  }

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
