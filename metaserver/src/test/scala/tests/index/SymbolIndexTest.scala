package tests.index

import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.nio.file.Paths
import scala.meta.languageserver.ScalametaLanguageServer
import scala.meta.languageserver.ServerConfig
import scala.meta.languageserver.search.InverseSymbolIndexer
import scala.meta.languageserver.search.SymbolIndex
import scala.{meta => m}
import langserver.messages.ClientCapabilities
import monix.execution.schedulers.TestScheduler
import org.langmeta.internal.io.PathIO
import org.langmeta.io.AbsolutePath
import org.langmeta.io.Classpath
import tests.MegaSuite
import utest._

object SymbolIndexTest extends MegaSuite {
  implicit val cwd: AbsolutePath =
    PathIO.workingDirectory.resolve("test-workspace")
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
      val reconstructedDatabase = InverseSymbolIndexer.reconstructDatabase(
        cwd,
        indexer.documents,
        indexer.symbols.allSymbols
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
  assert(Files.isRegularFile(path.toNIO))
  val path = cwd
    .resolve("a")
    .resolve("src")
    .resolve("test")
    .resolve("scala")
    .resolve("example")
    .resolve("UserTest.scala")
  val s = TestScheduler()
  val config = ServerConfig(
    cwd,
    setupScalafmt = false,
    indexJDK = false,
    indexClasspath = true // set to false to speedup edit/debug cycle
  )
  val client = new PipedOutputStream()
  val stdin = new PipedInputStream(client)
  val stdout = new PipedOutputStream()
  // TODO(olafur) run this as part of utest.runner.Framework.setup()
  while (s.tickOne()) () // Trigger indexing
  val server =
    new ScalametaLanguageServer(config, stdin, stdout, System.out)(s)
  server.initialize(0L, cwd.toString(), ClientCapabilities())
  val indexer: SymbolIndex = server.symbolIndexer

  override def utestAfterAll(): Unit = {
    println("Shutting down server...")
    server.shutdown()
    while (s.tickOne()) ()
    stdin.close()
  }
}
