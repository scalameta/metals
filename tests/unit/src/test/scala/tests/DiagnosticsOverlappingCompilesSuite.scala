package tests

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.ClientConfiguration
import scala.meta.internal.metals.Diagnostics
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.PreviouslyCompiledDownsteamTargets
import scala.meta.internal.metals.ScalaVersionSelector
import scala.meta.internal.metals.StatisticsConfig
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.clients.language.NoopLanguageClient
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.{bsp4j => b}

class DiagnosticsOverlappingCompilesSuite extends BaseSuite {

  private def setup(): (Diagnostics, AbsolutePath) = {
    val workspace =
      AbsolutePath(
        Files.createTempDirectory("metals-overlapping-compiles").toRealPath()
      )
    val path = workspace.resolve("Foo.scala")
    Files.write(path.toNIO, "object Foo".getBytes(StandardCharsets.UTF_8))
    val diagnostics = new Diagnostics(
      Buffers(),
      NoopLanguageClient,
      StatisticsConfig.default,
      Some(workspace),
      new ScalaVersionSelector(() => UserConfiguration(), BuildTargets.empty),
      BuildTargets.empty,
      new PreviouslyCompiledDownsteamTargets,
      MetalsServerConfig(),
      ClientConfiguration.default,
    )
    (diagnostics, path)
  }

  private val targetA = new BuildTargetIdentifier("file:///a")
  private val targetB = new BuildTargetIdentifier("file:///b")

  private def publishAll(
      diagnostics: Diagnostics,
      path: AbsolutePath,
      target: BuildTargetIdentifier,
  )(lines: List[Int], originId: String, isReset: Boolean): Unit = {
    val params = new b.PublishDiagnosticsParams(
      new b.TextDocumentIdentifier(path.toURI.toString),
      target,
      lines.map { line =>
        val diagnostic = new b.Diagnostic(
          new b.Range(new b.Position(line, 0), new b.Position(line, 1)),
          s"error $line",
        )
        diagnostic.setSeverity(b.DiagnosticSeverity.ERROR)
        diagnostic
      }.asJava,
      isReset,
    )
    params.setOriginId(originId)
    diagnostics.onBuildPublishDiagnostics(params)
  }

  private def publish(
      diagnostics: Diagnostics,
      path: AbsolutePath,
      target: BuildTargetIdentifier,
  )(line: Int, originId: String, isReset: Boolean): Unit =
    publishAll(diagnostics, path, target)(List(line), originId, isReset)

  private def finish(diagnostics: Diagnostics, target: BuildTargetIdentifier)(
      paramsOriginId: String,
      reportOriginId: String,
  ): Unit = {
    val report = new b.CompileReport(target, 1, 0)
    report.setOriginId(reportOriginId)
    diagnostics.onFinishCompileBuildTarget(
      report,
      b.StatusCode.ERROR,
      paramsOriginId,
    )
  }

  private def lines(diagnostics: Diagnostics, path: AbsolutePath): Seq[Int] =
    diagnostics.forFile(path).map(_.getRange.getStart.getLine)

  test("overlapping-compile-requests") {
    val (diagnostics, path) = setup()
    val send = publish(diagnostics, path, targetA) _
    // Bloop replays a deduplicated compilation to the second request.
    send(0, "first", true)
    send(1, "first", false)
    send(0, "second", true)
    send(2, "first", false)
    send(1, "second", false)
    send(2, "second", false)
    send(3, "first", false)
    send(3, "second", false)
    assertEquals(lines(diagnostics, path), Seq(0, 1, 2, 3))
  }

  test("single-compile-request") {
    val (diagnostics, path) = setup()
    val send = publish(diagnostics, path, targetA) _
    send(0, "first", true)
    send(1, "first", false)
    send(2, "first", false)
    assertEquals(lines(diagnostics, path), Seq(0, 1, 2))
  }

  test("reset-without-origin-id") {
    val (diagnostics, path) = setup()
    // Bloop clears stale problems without an origin id, then reports new ones.
    publishAll(diagnostics, path, targetA)(
      lines = Nil,
      originId = null,
      isReset = true,
    )
    val send = publish(diagnostics, path, targetA) _
    send(1, "first", false)
    send(2, "first", false)
    assertEquals(lines(diagnostics, path), Seq(1, 2))
  }

  test("no-reset-received") {
    val (diagnostics, path) = setup()
    val send = publish(diagnostics, path, targetA) _
    send(0, "first", false)
    send(1, "second", false)
    assertEquals(lines(diagnostics, path), Seq(0, 1))
  }

  test("next-request-after-finish") {
    val (diagnostics, path) = setup()
    val send = publish(diagnostics, path, targetA) _
    send(0, "first", true)
    finish(diagnostics, targetA)(
      paramsOriginId = "first",
      reportOriginId = "first",
    )
    send(1, "second", false)
    assertEquals(lines(diagnostics, path), Seq(0, 1))
  }

  test("mill-next-compile") {
    val (diagnostics, path) = setup()
    val send = publish(diagnostics, path, targetA) _
    // Mill streams problems, then resends them all with reset.
    send(0, "first", false)
    // Mill puts the origin id only in the compile report.
    finish(diagnostics, targetA)(
      paramsOriginId = null,
      reportOriginId = "first",
    )
    send(0, "first", true)
    finish(diagnostics, targetA)(
      paramsOriginId = null,
      reportOriginId = "first",
    )
    send(1, "second", false)
    assertEquals(lines(diagnostics, path), Seq(0, 1))
  }

  test("other-target-finished") {
    val (diagnostics, path) = setup()
    val send = publish(diagnostics, path, targetB) _
    send(0, "first", true)
    send(0, "second", true)
    // The second request also compiles target a, which finishes first.
    finish(diagnostics, targetA)(
      paramsOriginId = "second",
      reportOriginId = "second",
    )
    send(1, "first", false)
    send(1, "second", false)
    assertEquals(lines(diagnostics, path), Seq(0, 1))
  }
}
