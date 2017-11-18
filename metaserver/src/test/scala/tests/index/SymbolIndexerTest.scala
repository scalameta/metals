package tests.index

import java.net.URI
import java.nio.file.Files
import java.nio.file.Paths
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.meta.languageserver.Semanticdbs
import scala.meta.languageserver.SymbolIndexer
import scala.meta.languageserver.{index => i}
import scala.{meta => m}
import org.langmeta.inputs.Input
import org.langmeta.internal.io.FileIO
import org.langmeta.internal.io.PathIO
import org.langmeta.internal.semanticdb.{schema => s}
import org.langmeta.io.AbsolutePath
import org.langmeta.languageserver.InputEnrichments._
import tests.MegaSuite
import utest._

object SymbolIndexerTest extends MegaSuite {
  override val tests = Tests {
    implicit val cwd: AbsolutePath =
      PathIO.workingDirectory.resolve("test-workspace")
    val indexer = SymbolIndexer.empty(cwd)
    val path = cwd
      .resolve("a")
      .resolve("src")
      .resolve("test")
      .resolve("scala")
      .resolve("example")
      .resolve("UserTest.scala")
    assert(Files.isRegularFile(path.toNIO))
    val schemaDocuments = ListBuffer.empty[s.Document]

    FileIO
      .listAllFilesRecursively(cwd)
      .filter(_.toNIO.toString.endsWith(".semanticdb"))
      .foreach { p =>
        val db = Semanticdbs.loadFromFile(p)
        schemaDocuments ++= db.documents.map(
          // Wipe out irrelevant parts.
          _.withMessages(Nil).withSynthetics(Nil).withSymbols(Nil)
        )
        indexer.indexDatabase(db)
      }
    val testDatabase = s.Database(schemaDocuments.sortBy(_.filename)).toDb(None)

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
    "bijection" - {
      // Reconstruct an m.Database from the symbol index and asserts that the
      // reconstructed database is identical to the original semanticdbs that
      // built the symbol index.
      // TODO(olafur) handle local symbols when we stop indexing them.
      val db = mutable.Map.empty[String, m.Document]
      def get(filename: String) = db.getOrElseUpdate(
        filename,
        m.Document(
          Input.VirtualFile(
            filename,
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
      def handleResolvedName(
          uri: String,
          symbol: String,
          range: i.Range,
          definition: Boolean
      ): Unit = {
        val doc = get(uri)
        val pos = doc.input.fromIndexRange(range)
        val newDoc = doc.copy(
          names = m.ResolvedName(
            pos,
            m.Symbol(symbol),
            isDefinition = definition
          ) :: doc.names
        )
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
      val reconstructedDatabase = m
        .Database(
          db.values.iterator
            .filter(_.input.chars.nonEmpty)
            .toArray
            .sortBy(_.input.syntax)
        )
      val inputs = reconstructedDatabase.documents
        .map(
          d => Paths.get(d.input.syntax).getFileName.toString
        )
        .toList
      assert(
        inputs == List(
          "User.scala",
          "UserTest.scala"
        )
      )
      assertNoDiff(reconstructedDatabase.syntax, testDatabase.syntax)
    }
  }
}
