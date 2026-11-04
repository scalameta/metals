package pc

import java.net.URI
import java.util.Locale
import javax.tools.Diagnostic
import javax.tools.JavaFileObject

import scala.jdk.CollectionConverters._

import scala.meta.internal.jpc.JavaMetalsCompiler
import scala.meta.internal.jpc.JavaSemanticdbProvider
import scala.meta.internal.metals.CompilerVirtualFileParams
import scala.meta.internal.metals.ReportLevel
import scala.meta.internal.pc.EmptySymbolSearch
import scala.meta.internal.pc.PresentationCompilerConfigImpl
import scala.meta.pc.JavaFileManagerFactory
import scala.meta.pc.ProgressBars

import org.slf4j.LoggerFactory
import tests.pc.BaseJavaSemanticdbSuite
import tests.pc.TestingEmbeddedClient

class BatchSemanticdbDiagnosticSuite extends BaseJavaSemanticdbSuite {
  private val broken = CompilerVirtualFileParams(
    URI.create("file:///alpha/Api.java"),
    "package alpha;\npublic class Api {\n  MissingAlpha broken;\n}\n",
  )
  private val healthy = CompilerVirtualFileParams(
    URI.create("file:///beta/Api.java"),
    "package beta;\npublic class Api {\n  String clean(String x) { return x.trim(); }\n}\n",
  )
  private val otherBroken = CompilerVirtualFileParams(
    URI.create("file:///beta/Api.java"),
    "package beta;\n\npublic class Api {\n  MissingBeta broken;\n}\n",
  )

  for (reversed <- List(false, true)) {
    test(s"broken-and-healthy-same-basename-reversed-$reversed") {
      val expected = textDocument(broken.text, broken.uri)
      assert(expected.getDiagnosticsCount > 0)
      val params = List(broken, healthy)
      val documents =
        batchTextDocuments(if (reversed) params.reverse else params)
          .map(document => document.getUri -> document)
          .toMap
      assertEquals(
        documents.keySet,
        Set(broken.uri.toString, healthy.uri.toString),
      )
      assertEquals(
        documents(broken.uri.toString).getDiagnosticsList,
        expected.getDiagnosticsList,
      )
      val clean = documents(healthy.uri.toString)
      assertEquals(clean.getDiagnosticsCount, 0)
      assert(
        clean.getOccurrencesList.asScala.exists(
          _.getSymbol == "java/lang/String#trim()."
        )
      )
    }

    test(
      s"two-broken-same-basename-keep-their-own-diagnostics-reversed-$reversed"
    ) {
      val params = List(broken, otherBroken)
      val expected = params.map { param =>
        val document = textDocument(param.text, param.uri)
        assert(document.getDiagnosticsCount > 0)
        param.uri.toString -> document.getDiagnosticsList
      }.toMap
      val documents =
        batchTextDocuments(if (reversed) params.reverse else params)
      assertEquals(documents.size, 2)
      documents.foreach { document =>
        assertEquals(document.getDiagnosticsList, expected(document.getUri))
      }
    }
  }

  private def unpositioned(
      source: JavaFileObject,
      code: String,
  ): Diagnostic[JavaFileObject] = new Diagnostic[JavaFileObject] {
    override def getSource(): JavaFileObject = source
    override def getCode(): String = code
    override def getKind(): Diagnostic.Kind = Diagnostic.Kind.WARNING
    override def getMessage(locale: Locale): String = code
    override def getPosition(): Long = Diagnostic.NOPOS
    override def getStartPosition(): Long = Diagnostic.NOPOS
    override def getEndPosition(): Long = Diagnostic.NOPOS
    override def getLineNumber(): Long = Diagnostic.NOPOS
    override def getColumnNumber(): Long = Diagnostic.NOPOS
  }

  test("unpositioned-diagnostics-preserve-scope-without-invented-ranges") {
    val compiler = new JavaMetalsCompiler(
      buildTargetId = "",
      logger = LoggerFactory.getLogger(classOf[JavaMetalsCompiler]),
      reportsLevel = ReportLevel.Info,
      search = EmptySymbolSearch,
      embedded = new TestingEmbeddedClient(tmp),
      javaFileManagerFactory = JavaFileManagerFactory.EMPTY,
      metalsConfig = PresentationCompilerConfigImpl(emitDiagnostics = true),
      classpath = Nil,
      options = Nil,
      progressBars = ProgressBars.EMPTY,
    )
    try {
      val params = List(
        CompilerVirtualFileParams(
          URI.create("file:///One.java"),
          "class One {}",
        ),
        CompilerVirtualFileParams(
          URI.create("file:///Two.java"),
          "class Two {}",
        ),
      )
      val compile = compiler.batchCompilationTask(params).withAnalyzePhase()
      val localSource = compile.cu.getSourceFile
      compile.listener.report(unpositioned(null, "fixture.global"))
      compile.listener.report(unpositioned(localSource, "fixture.local"))
      val provider = new JavaSemanticdbProvider(compiler)
      assertEquals(compile.allCompilationUnits.size, 2)
      compile.allCompilationUnits.foreach { unit =>
        val document = provider.textDocumentFromSource(compile, unit, None)
        val diagnostics = document.getDiagnosticsList.asScala
        val expectedCodes =
          if (unit eq compile.cu) Set("fixture.global", "fixture.local")
          else Set("fixture.global")
        assertEquals(diagnostics.map(_.getCode).toSet, expectedCodes)
        assertEquals(diagnostics.size, expectedCodes.size)
        assert(diagnostics.forall(!_.hasRange))
      }
    } finally compiler.close()
  }
}
