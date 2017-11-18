package tests.index

import java.nio.file.Files
import scala.meta.languageserver.Semanticdbs
import scala.meta.languageserver.SymbolIndexer
import org.langmeta.internal.io.FileIO
import org.langmeta.internal.io.PathIO
import org.langmeta.io.AbsolutePath
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

    FileIO
      .listAllFilesRecursively(cwd)
      .filter(_.toNIO.toString.endsWith(".semanticdb"))
      .foreach { p =>
        val db = Semanticdbs.loadFromFile(p)
        indexer.indexDatabase(db)
      }

    "<<User>>(...)" - {
      val term = indexer.findSymbol(path, 3, 17).get
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
}
