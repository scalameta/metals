package tests.provider

import java.io.{FileOutputStream, PipedOutputStream, PrintStream}
import java.nio.file.Paths
import java.nio.file.{Files, Path}
import monix.execution.Scheduler.Implicits.global
import monix.reactive.Observable
import org.langmeta.inputs.Input
import org.langmeta.io.AbsolutePath
import org.langmeta.languageserver.InputEnrichments._
import scala.meta.jsonrpc.LanguageClient
import scala.meta.lsp.PublishDiagnostics
import tests.CompilerSuite
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.meta.internal.inputs._
import scala.meta.metals.{Configuration, Linter, Semanticdbs}
import scala.meta.metals.providers.DiagnosticsProvider

object DiagnosticsProviderTest extends CompilerSuite {
  val tmp: Path = Files.createTempDirectory("metals")
  val logFile = tmp.resolve("metals.log").toFile
  val out = new PrintStream(new FileOutputStream(logFile))
  val scalafixConfPath = ".customScalafixConfPath"
  val config = Observable.now(
    Configuration(
      scalac = Configuration.Scalac(
        diagnostics = Configuration.Enabled(true)
      ),
      scalafix = Configuration.Scalafix(
        enabled = true,
        confPath = Some(Paths.get(scalafixConfPath))
      )
    )
  )
  val stdout = new PipedOutputStream()
  implicit val client: LanguageClient =
    new LanguageClient(stdout, scribe.Logger.root)
  val diagnosticsProvider = new DiagnosticsProvider(config, AbsolutePath(tmp))
  Files.write(
    tmp.resolve(scalafixConfPath),
    """
      |rules = [ NoInfer ]
    """.stripMargin.getBytes()
  )
  val linter: Linter = new Linter(config, AbsolutePath(tmp))
  def check(name: String, original: String, expected: String): Unit = {
    // NOTE. This test is disabled because it is flaky due to unstable
    // behavior of the presentation compiler. To re-enable this test,
    // replace `ignore` with `test`
    ignore(name) {
      val input = Input.VirtualFile(name, original)
      val doc = Semanticdbs.toSemanticdb(input, compiler)
      val result = diagnosticsProvider.diagnostics(doc).runSyncMaybe
      val (PublishDiagnostics(_, diagnostics) :: Nil) =
        Await.result(diagnosticsProvider.diagnostics(doc).runAsync, 1.second)
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
