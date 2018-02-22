package tests.compiler

import java.io.FileOutputStream
import java.io.PipedOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import scala.meta.internal.inputs._
import scala.meta.metals.Configuration
import scala.meta.metals.Linter
import scala.meta.metals.Semanticdbs
import org.langmeta.lsp.PublishDiagnostics
import scala.meta.metals.providers.SquiggliesProvider
import monix.execution.Scheduler.Implicits.global
import monix.reactive.Observable
import org.langmeta.inputs.Input
import org.langmeta.io.AbsolutePath
import org.langmeta.io.RelativePath
import org.langmeta.languageserver.InputEnrichments._
import org.langmeta.lsp.LanguageClient
import com.typesafe.scalalogging.LazyLogging
import scala.concurrent.Await
import scala.concurrent.duration._

object SquiggliesTest extends CompilerSuite with LazyLogging {
  val tmp: Path = Files.createTempDirectory("metals")
  val logFile = tmp.resolve("metals.log").toFile
  val out = new PrintStream(new FileOutputStream(logFile))
  val scalafixConfPath = ".customScalafixConfPath"
  val config = Observable.now(
    Configuration(
      scalafix = Configuration.Scalafix(confPath = Some(RelativePath(scalafixConfPath)))
    )
  )
  val stdout = new PipedOutputStream()
  implicit val client = new LanguageClient(stdout, logger)
  val squiggliesProvider = new SquiggliesProvider(config, AbsolutePath(tmp))
  Files.write(
    tmp.resolve(scalafixConfPath),
    """
      |rules = [ NoInfer ]
    """.stripMargin.getBytes()
  )
  val linter: Linter = new Linter(config, AbsolutePath(tmp))
  def check(name: String, original: String, expected: String): Unit = {
    test(name) {
      val input = Input.VirtualFile(name, original)
      val doc = Semanticdbs.toSemanticdb(input, compiler)
      val result = squiggliesProvider.squigglies(doc).runSyncMaybe
      val (PublishDiagnostics(_, diagnostics) :: Nil) =
        Await.result(squiggliesProvider.squigglies(doc).runAsync, 1.second)
      val obtained = diagnostics.map { d =>
        val pos = input.toPosition(d.range)
        pos.formatMessage(d.severity.getOrElse(???).toString, d.message)
      }
      assertNoDiff(obtained.mkString("\n"), expected)
    }
  }

  check(
    "NoInfer.scala",
    """
      |object a {
      |  List(1, "")
      |}
    """.stripMargin,
    """
      |NoInfer.scala:3: Error: Inferred Any
      |  List(1, "")
      |      ^
    """.stripMargin
  )

  check(
    "ParseError.scala",
    """
      |object b {
      |  List(
      |}
    """.stripMargin,
    """
      |ParseError.scala:4: Error: illegal start of simple expression
      |}
      |^
    """.stripMargin
  )

  check(
    "Combined.scala",
    """
      |object b {
      |  List(1, "")
      |  val x: Int = ""
      |}
    """.stripMargin,
    """
      |Combined.scala:4: Error: type mismatch;
      | found   : String("")
      | required: Int
      |  val x: Int = ""
      |               ^
      |Combined.scala:3: Error: Inferred Any
      |  List(1, "")
      |      ^
    """.stripMargin
  )

  // negative teest: the presentation compiler only runs up to typer
  // and deprecation warnings are reported during a later phase refchecks.
  check(
    "deprecation.scala",
    """
      |import scala.collection.mutable.Stack
      |object c {
      |  val x: Stack[Int] = ???
      |}
    """.stripMargin,
    ""
  )

  check(
    "UnusedImport.scala",
    """
      |import scala.concurrent.Future
      |object d {
      |}
    """.stripMargin,
    """
      |UnusedImport.scala:2: Warning: Unused import
      |import scala.concurrent.Future
      |                        ^
    """.stripMargin
  )

}
