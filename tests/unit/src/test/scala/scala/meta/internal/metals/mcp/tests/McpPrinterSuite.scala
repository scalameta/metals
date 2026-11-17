package scala.meta.internal.metals.mcp.tests

import scala.meta.internal.metals.mcp.McpPrinter._

import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import tests.BaseSuite

class McpPrinterSuite extends BaseSuite {

  private val diagnostics = Seq("First error", "Second error").map { message =>
    val diagnostic = new Diagnostic()
    diagnostic.setRange(new Range(new Position(), new Position()))
    diagnostic.setSeverity(DiagnosticSeverity.Error)
    diagnostic.setMessage(message)
    diagnostic
  }

  Seq("zero" -> 0, "negative" -> -1).foreach { case (name, limit) =>
    test(s"$name-cap-still-shows-a-diagnostic") {
      assertNoDiff(
        diagnostics.show(limit),
        """|L0:C0-L0:C0: [Error]
           |First error
           |
           |[Showing 1 of 2 errors and warnings. Fix these and compile again.]
           |""".stripMargin,
      )
    }
  }

  test("zero-cap-does-not-truncate-a-single-diagnostic") {
    assertNoDiff(
      diagnostics.take(1).show(limit = 0),
      """|L0:C0-L0:C0: [Error]
         |First error
         |""".stripMargin,
    )
  }

  test("zero-cap-without-diagnostics") {
    assertEquals(Seq.empty[Diagnostic].show(limit = 0), "")
  }
}
