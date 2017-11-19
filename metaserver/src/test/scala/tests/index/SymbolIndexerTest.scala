package tests.index

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.URI
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.meta.languageserver.ScalametaLanguageServer
import scala.meta.languageserver.Semanticdbs
import scala.meta.languageserver.ServerConfig
import scala.meta.languageserver.SymbolIndexer
import scala.meta.languageserver.ctags.Ctags
import scala.meta.languageserver.{index => i}
import scala.{meta => m}
import langserver.core.MessageReader
import langserver.core.MessageWriter
import langserver.messages.ClientCapabilities
import langserver.messages.DefinitionResult
import langserver.messages.TextDocumentDefinitionRequest
import langserver.messages.TextDocumentPositionParams
import langserver.types.TextDocumentIdentifier
import org.langmeta.inputs.Input
import org.langmeta.internal.io.FileIO
import org.langmeta.internal.io.PathIO
import org.langmeta.internal.semanticdb.{schema => s}
import langserver.{types => l}
import org.langmeta.io.AbsolutePath
import org.langmeta.languageserver.InputEnrichments._
import org.langmeta.semanticdb.Database
import tests.MegaSuite
import utest._
import monix.execution.schedulers.TestScheduler
import org.langmeta.io.Classpath
import play.api.libs.json.Format
import play.api.libs.json.JsValue
import play.api.libs.json.Json

object SymbolIndexerTest extends MegaSuite {
  implicit val cwd: AbsolutePath =
    PathIO.workingDirectory.resolve("test-workspace")
  val path = cwd
    .resolve("a")
    .resolve("src")
    .resolve("test")
    .resolve("scala")
    .resolve("example")
    .resolve("UserTest.scala")
  assert(Files.isRegularFile(path.toNIO))

  val scheduler = TestScheduler()
  val config = ServerConfig(
    cwd,
    setupScalafmt = false,
    indexJDK = false,
    indexClasspath = true // set to false to speedup edit/debug cycle
  )
  val client = new PipedOutputStream()
  val stdin = new PipedInputStream(client)
  val stdout = new PipedOutputStream()

  val server =
    new ScalametaLanguageServer(config, stdin, stdout, System.out)(scheduler)
  server.initialize(0L, cwd.toString(), ClientCapabilities())
  // TODO(olafur) run this as part of utest.runner.Framework.setup()
  while (scheduler.tickOne()) () // Trigger indexing
  val indexer: SymbolIndexer = server.symbolIndexer

  override val tests = Tests {

    def assertSymbolFound(
        line: Int,
        column: Int,
        expected: String
    ): Unit = {
      val term = indexer.findSymbol(path, line, column)
      Predef.assert(term.isDefined, s"Symbol not found at $path:$line:$column")
      assertNoDiff(term.get.symbol, expected)
      Predef.assert(
        term.get.definition.isDefined,
        s"Definition not found for term $term"
      )
    }

    "fallback" - {
      "<<User>>(...)" -
        assertSymbolFound(3, 17, "_root_.a.User#")
      "User.<<apply>>(...)" -
        assertSymbolFound(3, 22, "_root_.a.User#")
      "User.apply(<<name>> ...)" -
        assertSymbolFound(3, 28, "_root_.a.User#(name)")
      "user.copy(<<age>> = ...)" -
        assertSymbolFound(4, 14, "_root_.a.User#(age)")
    }

    "classpath" - {
      "<<List>>(...)" - // ScalaCtags
        assertSymbolFound(5, 5, "_root_.scala.collection.immutable.List.")
      "<<CharRef>>.create(...)" - // JavaCtags
        assertSymbolFound(8, 19, "_root_.scala.runtime.CharRef.")
    }

    "bijection" - {
      val target = cwd.resolve("a").resolve("target").resolve("scala-2.12")
      val originalDatabase = {
        val complete = m.Database.load(
          Classpath(
            target.resolve("classes") ::
              target.resolve("test-classes") ::
              Nil
          )
        )
        val slimDocuments = complete.documents.map { d =>
          d.copy(messages = Nil, synthetics = Nil, symbols = Nil)
        }
        m.Database(slimDocuments)
      }
      println(originalDatabase.toString())
      // Reconstruct an m.Database from the symbol index and asserts that the
      // reconstructed database is identical to the original semanticdbs that
      // built the symbol index.
      // TODO(olafur) handle local symbols when we stop indexing them.
      val db = mutable.Map.empty[String, m.Document]
      def get(filename: String) = {
        val key = if (filename.startsWith("file")) {
          cwd.toNIO.relativize(Paths.get(URI.create(filename))).toString
        } else filename
        db.getOrElseUpdate(
          key,
          m.Document(
            Input.VirtualFile(
              key,
              indexer.documents
                .getDocument(URI.create(filename))
                .fold("")(_.contents)
            ),
            "Scala212",
            Nil,
            Nil,
            Nil,
            Nil
          )
        )
      }
      def handleResolvedName(
          uri: String,
          symbol: String,
          range: i.Range,
          definition: Boolean
      ): Unit = {
        val doc = get(uri)
        val pos = doc.input.toPosition(range)
        val rs =
          m.ResolvedName(pos, m.Symbol(symbol), isDefinition = definition)
        val newDoc = doc.copy(names = rs :: doc.names)
        db(doc.input.syntax) = newDoc
      }
      indexer.symbols.index.foreach { symbol =>
        symbol.definition.collect {
          case i.Position(uri, Some(range)) =>
            handleResolvedName(uri, symbol.symbol, range, definition = true)
        }
        symbol.references.collect {
          case (uri, ranges) =>
            ranges.ranges.foreach { range =>
              handleResolvedName(uri, symbol.symbol, range, definition = false)
            }
        }
      }
      val reconstructedDatabase = m.Database(
        db.values.iterator
          .filter(!_.input.syntax.startsWith("jar:"))
          .filter(_.input.chars.nonEmpty)
          .toArray
          .sortBy(_.input.syntax)
      )
      val filenames = reconstructedDatabase.documents.toIterator.map { d =>
        Paths.get(d.input.syntax).getFileName.toString
      }.toList
      assert(filenames.nonEmpty)
      assert(
        filenames == List(
          "User.scala",
          "UserTest.scala"
        )
      )
      assertNoDiff(reconstructedDatabase.syntax, originalDatabase.syntax)
    }
  }
  override def utestAfterAll(): Unit = {
    println("Shutting down server...")
    server.shutdown()
    while (scheduler.tickOne()) ()
    stdin.close()
  }
}
