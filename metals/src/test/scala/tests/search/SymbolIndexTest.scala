package tests.search

import java.io.PipedOutputStream
import java.nio.file.Files
import java.nio.file.Paths
import scala.meta.metals.MSchedulers
import scala.meta.metals.ScalametaEnrichments._
import scala.meta.metals.MetalsServices
import scala.meta.metals.Uri
import scala.meta.metals.internal.BuildInfo
import org.langmeta.{lsp => l}
import org.langmeta.lsp.ClientCapabilities
import org.langmeta.lsp.InitializeParams
import org.langmeta.lsp.Location
import org.langmeta.lsp.Position
import org.langmeta.lsp.Range
import scala.meta.metals.search.InMemorySymbolIndex
import scala.meta.metals.search.InverseSymbolIndexer
import scala.meta.metals.search.SymbolIndex
import scala.{meta => m}
import com.typesafe.scalalogging.LazyLogging
import monix.execution.schedulers.TestScheduler
import org.langmeta.io.AbsolutePath
import org.langmeta.io.Classpath
import org.langmeta.lsp.LanguageClient
import org.langmeta.semanticdb.Symbol
import tests.MegaSuite
import utest._

object SymbolIndexTest extends MegaSuite with LazyLogging {
  implicit val cwd: AbsolutePath =
    AbsolutePath(BuildInfo.testWorkspaceBaseDirectory)
  object path {
    val User = cwd
      .resolve("src")
      .resolve("main")
      .resolve("scala")
      .resolve("example")
      .resolve("User.scala")
    val UserUri = Uri(User)
    val UserReferenceLine = 3

    val UserTest = cwd
      .resolve("src")
      .resolve("test")
      .resolve("scala")
      .resolve("example")
      .resolve("UserTest.scala")
    val UserTestUri = Uri(UserTest)
  }
  Predef.assert(
    Files.isRegularFile(path.User.toNIO),
    path.User.toString()
  )
  Predef.assert(
    Files.isRegularFile(path.UserTest.toNIO),
    path.UserTest.toString()
  )
  val s = TestScheduler()
  val mscheduler = new MSchedulers(s, s, s)
  val stdout = new PipedOutputStream()
  // TODO(olafur) run this as part of utest.runner.Framework.setup()
  val client = new LanguageClient(stdout, logger)
  val metals = new MetalsServices(cwd, client, mscheduler)
  metals
    .initialize(InitializeParams(None, cwd.toString(), ClientCapabilities()))
    .runAsync(s)
  while (s.tickOne()) () // Trigger indexing
  val index: SymbolIndex = metals.symbolIndex
  val reminderMsg = "Did you run metalsSetup from sbt?"
  override val tests = Tests {

    /** Checks that there is a symbol at given position, it's in the index and has expected name */
    def assertSymbolFound(line: Int, column: Int)(
        expected: String
    ): Symbol = {
      val (symbol, _) = index
        .findSymbol(path.UserTestUri, line, column)
        .getOrElse(
          fail(
            s"Symbol not found at $path.UserTest:$line:$column. ${reminderMsg}"
          )
        )
      assertNoDiff(symbol.syntax, expected)
      val symbolData = index
        .referencesData(symbol)
        .headOption
        .getOrElse(
          fail(s"Symbol ${symbol} is not found in the index. ${reminderMsg}")
        )
      assertNoDiff(symbolData.symbol, expected)
      symbol
    }

    /** Checks that given symbol has a definition with expected name */
    def assertSymbolDefinition(line: Int, column: Int)(
        expectedSymbol: String,
        expectedDefn: String
    ): Unit = {
      val symbol = assertSymbolFound(line, column)(expectedSymbol)
      val data = index
        .definitionData(symbol)
        .getOrElse(
          fail(s"Definition not found for term ${symbol}")
        )
      assertNoDiff(data.symbol, expectedDefn)
    }

    /** Checks that given symbol has a definition with expected name */
    def assertSymbolReferences(
        line: Int,
        column: Int,
        withDefinition: Boolean
    )(
        expected: Location*
    ): Unit = {
      val (symbol, _) = index
        .findSymbol(path.UserTestUri, line, column)
        .getOrElse(
          fail(
            s"Symbol not found at $path.UserTest:$line:$column. ${reminderMsg}"
          )
        )
      val dataList = index.referencesData(symbol)
      if (dataList.isEmpty) fail(s"References not found for term ${symbol}")
      // TODO: use `dataList` to test expected alternatives
      val found = for {
        data <- dataList
        pos <- data.referencePositions(withDefinition)
      } yield pos.toLocation

      val missingLocations = found.toSet diff expected.toSet
      assert(missingLocations.isEmpty)
      val unexpectedLocations = expected.toSet diff found.toSet
      assert(unexpectedLocations.isEmpty)
    }

    def ref(
        path: AbsolutePath,
        start: (Int, Int),
        end: (Int, Int)
    ): Location =
      l.Location(
        s"file:${path.toString}",
        Range(
          Position(start._1, start._2),
          l.Position(end._1, end._2)
        )
      )

    "definition" - {
      "<<User>>(...)" -
        assertSymbolDefinition(path.UserReferenceLine, 17)(
          "_root_.a.User.",
          "_root_.a.User#"
        )
      "User.<<apply>>(...)" -
        assertSymbolDefinition(3, 22)(
          "_root_.a.User.apply(Ljava/lang/String;I)La/User;.",
          "_root_.a.User#"
        )
      "User.<<copy>>(...)" -
        assertSymbolDefinition(4, 9)(
          "_root_.a.User#copy(Ljava/lang/String;I)La/User;.",
          "_root_.a.User#"
        )
      "User.apply(<<name>> ...)" -
        assertSymbolDefinition(3, 28)(
          "_root_.a.User.apply(Ljava/lang/String;I)La/User;.(name)",
          "_root_.a.User#(name)"
        )
      "user.copy(<<age>> = ...)" -
        assertSymbolDefinition(4, 14)(
          "_root_.a.User#copy(Ljava/lang/String;I)La/User;.(age)",
          "_root_.a.User#(age)"
        )
    }

    "classpath" - {
      "<<List>>(...)" - // ScalaMtags
        assertSymbolFound(5, 5)("_root_.scala.collection.immutable.List.")
      "<<CharRef>>.create(...)" - // JavaMtags
        assertSymbolFound(8, 19)("_root_.scala.runtime.CharRef.")
    }

    "references" - {
      "<<User>>(...)" -
        assertSymbolReferences(3, 17, withDefinition = true)(
          ref(path.User, (2, 11), (2, 15)),
          ref(path.UserTest, (3, 15), (3, 19))
        )
      "<<List>>" -
        assertSymbolReferences(5, 5, withDefinition = false)(
          ref(path.User, (6, 10), (6, 14)),
          ref(path.UserTest, (5, 2), (5, 6))
        )
      "a.a.<<x>>" -
        assertSymbolReferences(9, 28, withDefinition = true)(
          ref(path.User, (5, 6), (5, 7)),
          ref(path.User, (6, 18), (6, 19)),
          ref(path.UserTest, (9, 28), (9, 29))
        )
      "User.apply(<<name>> ...)" -
        assertSymbolReferences(3, 27, withDefinition = false)(
          // ref(path.User,     (2,16),  (2,20)), // definition
          ref(path.UserTest, (3, 26), (3, 30)),
          ref(path.UserTest, (9, 17), (9, 21))
        )
    }

    "workspace" - {
      def checkQuery(
          expected: String*
      )(implicit path: utest.framework.TestPath): Unit = {
        while (s.tickOne()) ()
        val result = metals.symbolIndex.workspaceSymbols(path.value.last)
        val obtained = result.toIterator.map(_.name).mkString("\n")
        assertNoDiff(obtained, expected.mkString("\n"))
      }
      "EmptyResult" - checkQuery()
      "User" - checkQuery("User", "UserTest")
      "Test" - checkQuery("UserTest")
    }

    "bijection" - {
      val target = cwd.resolve("target").resolve("scala-2.12")
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
      index match {
        case index: InMemorySymbolIndex =>
          val reconstructedDatabase = InverseSymbolIndexer.reconstructDatabase(
            cwd,
            index.documentIndex,
            index.symbolIndexer.allSymbols
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
        case _ =>
          fail(s"Unsupported index ${index.getClass}")
      }
    }

    "edit-distance" - {
      val user = path.UserTestUri.toInput(metals.buffers)
      val newUser = user.copy(value = "// leading comment\n" + user.value)
      metals.buffers.changed(newUser)
      assertSymbolDefinition(path.UserReferenceLine + 1, 17)(
        "_root_.a.User.",
        "_root_.a.User#"
      )
    }
  }

  override def utestAfterAll(): Unit = {
    println("Shutting down server...")
    metals.shutdown()
    while (s.tickOne()) ()
  }
}
