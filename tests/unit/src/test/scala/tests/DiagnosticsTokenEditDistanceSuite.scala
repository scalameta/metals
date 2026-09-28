package tests

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.ClientConfiguration
import scala.meta.internal.metals.Diagnostics
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.PreviouslyCompiledDownsteamTargets
import scala.meta.internal.metals.ScalaVersionSelector
import scala.meta.internal.metals.StatisticsConfig
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.clients.language.NoopLanguageClient
import scala.meta.internal.parsing.TokenEditDistance
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.{lsp4j => l}

/**
 * Unit-level regression tests for how `Diagnostics.publishDiagnostics`
 * recomputes `TokenEditDistance`.
 *
 * `TokenEditDistance` for a file only depends on (snapshot, current buffer),
 * so it should be computed once per publish, not once per diagnostic.
 */
class DiagnosticsTokenEditDistanceSuite extends BaseSuite {

  /** A `Buffers` that counts how many times `tokenEditDistance` is invoked. */
  private class CountingBuffers extends Buffers {
    val calls: AtomicInteger = new AtomicInteger()
    override def tokenEditDistance(
        source: AbsolutePath,
        snapshot: String,
        scalaVersionSelector: ScalaVersionSelector,
    ): TokenEditDistance = {
      calls.incrementAndGet()
      super.tokenEditDistance(source, snapshot, scalaVersionSelector)
    }
  }

  private def newDiagnostics(
      buffers: Buffers,
      client: NoopLanguageClient,
      workspace: AbsolutePath,
  ): Diagnostics = {
    val selector =
      new ScalaVersionSelector(() => UserConfiguration(), BuildTargets.empty)
    new Diagnostics(
      buffers,
      client,
      StatisticsConfig.default,
      Some(workspace),
      selector,
      BuildTargets.empty,
      new PreviouslyCompiledDownsteamTargets,
      MetalsServerConfig(),
      ClientConfiguration.default,
    )
  }

  private def tempWorkspace(): AbsolutePath =
    AbsolutePath(Files.createTempDirectory("metals-diag-ted"))

  private def write(path: AbsolutePath, text: String): Unit = {
    Files.write(path.toNIO, text.getBytes(StandardCharsets.UTF_8))
    ()
  }

  test("computed-once-per-publish") {
    val workspace = tempWorkspace()
    val path = workspace.resolve("Foo.scala")
    // A file that tokenizes as real Scala so the diff actually runs.
    val snapshot =
      (0 until 40)
        .map(i => s"  val v$i: Int = 0")
        .mkString("object Foo {\n", "\n", "\n}\n")
    write(path, snapshot)

    val buffers = new CountingBuffers
    // Buffer diverges from the snapshot => TokenEditDistance can't short-circuit
    // to `Unchanged`; it really tokenizes both sides and runs DiffUtils.diff.
    buffers.put(path, "// edited\n" + snapshot)

    val diagnostics = newDiagnostics(buffers, NoopLanguageClient, workspace)

    val n = 50
    val diags = (0 until n).toList.map { i =>
      new l.Diagnostic(
        new l.Range(new l.Position(1, 6), new l.Position(1, 7)),
        s"unused $i",
        l.DiagnosticSeverity.Warning,
        "scalac",
      )
    }
    diagnostics.onPublishDiagnostics(
      path,
      diags,
      isReset = true,
      originId = "build",
    )

    // Isolate the cost of a single keystroke.
    buffers.calls.set(0)
    diagnostics.didChange(path)

    // DESIRED behavior after the fix: one TokenEditDistance computation per
    // publish, regardless of the number of diagnostics. This currently FAILS
    // because the work is repeated N times (see the `n` assertion in history).
    assertEquals(buffers.calls.get(), 1)
  }

  test("shifts-ranges-when-buffer-has-inserted-lines") {
    val workspace = tempWorkspace()
    val path = workspace.resolve("Bar.scala")
    // Snapshot on disk (what the build server compiled), `x` on line 1.
    val snapshot =
      """|object Bar {
         |  val x = 1
         |}
         |""".stripMargin
    write(path, snapshot)

    val buffers = Buffers()
    // The user inserted one line above the code, so every token shifts down by 1.
    buffers.put(
      path,
      """|// inserted
         |object Bar {
         |  val x = 1
         |}
         |""".stripMargin,
    )

    val published = new AtomicReference[PublishDiagnosticsParams]()
    val client = new NoopLanguageClient {
      override def publishDiagnostics(
          params: PublishDiagnosticsParams
      ): Unit = published.set(params)
    }
    val diagnostics = newDiagnostics(buffers, client, workspace)

    // Build diagnostic points at `x` in the snapshot: line 1, columns 6..7.
    val diag = new l.Diagnostic(
      new l.Range(new l.Position(1, 6), new l.Position(1, 7)),
      "unused warning",
      l.DiagnosticSeverity.Warning,
      "scalac",
    )
    diagnostics.onPublishDiagnostics(
      path,
      List(diag),
      isReset = true,
      originId = "build",
    )

    val params = published.get()
    assert(params != null, "expected diagnostics to be published")
    assertEquals(params.getDiagnostics().size(), 1)
    val range = params.getDiagnostics().get(0).getRange()
    // The range must be shifted down by the one inserted line: line 1 -> line 2.
    assertEquals(range.getStart().getLine(), 2)
    assertEquals(range.getStart().getCharacter(), 6)
    assertEquals(range.getEnd().getLine(), 2)
    assertEquals(range.getEnd().getCharacter(), 7)
  }
}
