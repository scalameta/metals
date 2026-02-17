package tests.pc

import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

import scala.meta.internal.jsemanticdb.Semanticdb
import scala.meta.internal.mtags.SemanticdbPrinter
import scala.meta.internal.proto.binder.Binder
import scala.meta.internal.proto.diag.ProtoError
import scala.meta.internal.proto.diag.SourceFile
import scala.meta.internal.proto.parse.Parser

import munit.Location
import munit.TestOptions

class BaseProtoDiagnosticsSuite extends BaseProtoPCSuite {

  /**
   * Check diagnostics using SemanticDB format and SemanticdbPrinter.
   * This produces output in the same format as other Metals tests.
   */
  def check(
      testOpt: TestOptions,
      original: String,
      expected: String,
      filename: String = "test.proto",
  )(implicit loc: Location): Unit = {
    test(testOpt) {
      val doc = parseToSemanticdb(original, filename)
      val obtained = SemanticdbPrinter.printDocument(doc)
      assertNoDiff(obtained, expected)
    }
  }

  def checkNoDiagnostics(
      testOpt: TestOptions,
      original: String,
      filename: String = "test.proto",
  )(implicit loc: Location): Unit = {
    test(testOpt) {
      val doc = parseToSemanticdb(original, filename)
      val diagnostics = doc.getDiagnosticsList.asScala.toList
      assert(
        diagnostics.isEmpty,
        s"Expected no diagnostics, but got: ${diagnostics.map(_.getMessage).mkString(", ")}",
      )
    }
  }

  /**
   * Parse proto, bind symbols, and generate SemanticDB with diagnostics.
   */
  private def parseToSemanticdb(
      code: String,
      filename: String,
  ): Semanticdb.TextDocument = {
    val builder = Semanticdb.TextDocument
      .newBuilder()
      .setSchema(Semanticdb.Schema.SEMANTICDB4)
      .setUri(filename)
      .setText(code)
      .setLanguage(Semanticdb.Language.UNKNOWN_LANGUAGE)

    try {
      val source = new SourceFile(filename, code)
      val file = Parser.parse(source)

      // Run binding to detect name resolution errors
      val bindingResult = Binder.bindWithErrors(file)
      for (error <- bindingResult.errors().asScala) {
        val typeRef = error.typeRef()
        val line = source.offsetToLine(error.position())
        val column = source.offsetToColumn(error.position())
        builder.addDiagnostics(
          Semanticdb.Diagnostic
            .newBuilder()
            .setRange(
              Semanticdb.Range
                .newBuilder()
                .setStartLine(line)
                .setStartCharacter(column)
                .setEndLine(line)
                .setEndCharacter(column + typeRef.fullName().length())
                .build()
            )
            .setSeverity(Semanticdb.Diagnostic.Severity.ERROR)
            .setMessage(error.message())
            .build()
        )
      }
    } catch {
      case e: ProtoError =>
        builder.addDiagnostics(
          Semanticdb.Diagnostic
            .newBuilder()
            .setRange(
              Semanticdb.Range
                .newBuilder()
                .setStartLine(e.line())
                .setStartCharacter(e.column())
                .setEndLine(e.line())
                .setEndCharacter(e.column() + 1)
                .build()
            )
            .setSeverity(Semanticdb.Diagnostic.Severity.ERROR)
            .setMessage(e.rawMessage())
            .build()
        )
      case NonFatal(e) =>
        builder.addDiagnostics(
          Semanticdb.Diagnostic
            .newBuilder()
            .setRange(
              Semanticdb.Range
                .newBuilder()
                .setStartLine(0)
                .setStartCharacter(0)
                .setEndLine(0)
                .setEndCharacter(0)
                .build()
            )
            .setSeverity(Semanticdb.Diagnostic.Severity.ERROR)
            .setMessage(s"Parse error: ${e.getMessage}")
            .build()
        )
    }

    builder.build()
  }
}
