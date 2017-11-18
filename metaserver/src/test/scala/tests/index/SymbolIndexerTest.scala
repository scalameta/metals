package tests.index

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.net.URLClassLoader
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
import langserver.messages.ClientCapabilities
import org.langmeta.inputs.Input
import org.langmeta.internal.io.FileIO
import org.langmeta.internal.io.PathIO
import org.langmeta.internal.semanticdb.{schema => s}
import org.langmeta.io.AbsolutePath
import org.langmeta.languageserver.InputEnrichments._
import org.langmeta.semanticdb.Database
import tests.MegaSuite
import utest._
import monix.execution.schedulers.TestScheduler

//object ServerTest extends MegaSuite {
//  val server =
//    new ScalametaLanguageServer(cwd, System.in, System.out, System.out)
//  server.initialize(0L, cwd.toString(), ClientCapabilities())
//  val symbols = server.symbolIndexer
//  test("server")
//}
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
//  val schemaDocuments = ListBuffer.empty[s.Document]

  val scheduler = TestScheduler()
  val config = ServerConfig(cwd, indexJDK = false)
  val stdin = new ByteArrayInputStream(Array.empty)
  val server = new ScalametaLanguageServer(
    config,
    stdin,
    System.out,
    System.out
  )(scheduler)
  server.initialize(0L, cwd.toString(), ClientCapabilities())
  while (scheduler.tickOne()) () // Trigger indexing
  val indexer: SymbolIndexer = server.symbolIndexer

//  val testDatabase: Database =
//    s.Database(schemaDocuments.sortBy(_.filename)).toDb(None)

  override val tests = Tests {

    "fallback" - {
      "<<User>>(...)" - {
        val term = indexer.findSymbol(path, 3, 17).get
        assertNoDiff(term.symbol, "_root_.a.User#")
        assert(term.definition.isDefined)
      }

      "User.<<apply>>(...)" - {
        val term = indexer.findSymbol(path, 3, 22).get
        assertNoDiff(term.symbol, "_root_.a.User#")
        assert(term.definition.isDefined)
      }

      "User.apply(<<name>> ...)" - {
        val term = indexer.findSymbol(path, 3, 28).get
        assertNoDiff(term.symbol, "_root_.a.User#(name)")
        assert(term.definition.isDefined)
      }

      "user.copy(<<age>> = ...)" - {
        val term = indexer.findSymbol(path, 4, 14).get
        assertNoDiff(term.symbol, "_root_.a.User#(age)")
        assert(term.definition.isDefined)
      }
    }

    "classpath" - {
      "<<List>>(...)" - {
        val term = indexer.findSymbol(path, 5, 5).get
        assertNoDiff(term.symbol, "_root_.scala.collection.immutable.List.")
        assert(term.definition.isDefined)
      }
    }
//    "bijection" - {
//      // Reconstruct an m.Database from the symbol index and asserts that the
//      // reconstructed database is identical to the original semanticdbs that
//      // built the symbol index.
//      // TODO(olafur) handle local symbols when we stop indexing them.
//      val db = mutable.Map.empty[String, m.Document]
//      def get(filename: String) = db.getOrElseUpdate(
//        filename,
//        m.Document(
//          Input.VirtualFile(
//            filename,
//            indexer.documents
//              .getDocument(URI.create(filename))
//              .fold("")(_.contents)
//          ),
//          "Scala212",
//          Nil,
//          Nil,
//          Nil,
//          Nil
//        )
//      )
//      def handleResolvedName(
//          uri: String,
//          symbol: String,
//          range: i.Range,
//          definition: Boolean
//      ): Unit = {
//        val doc = get(uri)
//        val pos = doc.input.fromIndexRange(range)
//        val newDoc = doc.copy(
//          names = m.ResolvedName(
//            pos,
//            m.Symbol(symbol),
//            isDefinition = definition
//          ) :: doc.names
//        )
//        db(doc.input.syntax) = newDoc
//      }
//      indexer.symbols.index.foreach { symbol =>
//        symbol.definition.collect {
//          case i.Position(uri, Some(range)) =>
//            handleResolvedName(uri, symbol.symbol, range, definition = true)
//        }
//        symbol.references.collect {
//          case (uri, ranges) =>
//            ranges.ranges.foreach { range =>
//              handleResolvedName(uri, symbol.symbol, range, definition = false)
//            }
//        }
//      }
//      val reconstructedDatabase = m
//        .Database(
//          db.values.iterator
//            .filter(_.input.chars.nonEmpty)
//            .toArray
//            .sortBy(_.input.syntax)
//        )
//      val inputs = reconstructedDatabase.documents
//        .map(
//          d => Paths.get(d.input.syntax).getFileName.toString
//        )
//        .toList
//      assert(
//        inputs == List(
//          "User.scala",
//          "UserTest.scala"
//        )
//      )
//      assertNoDiff(reconstructedDatabase.syntax, testDatabase.syntax)
//    }
  }
  override def utestAfterAll(): Unit = {
    println("Shutting down server...")
    server.shutdown()
    while (scheduler.tickOne()) ()
    stdin.close()
  }
}
