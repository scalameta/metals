package tests.j

import java.nio.file.Paths

import scala.meta.inputs.Input
import scala.meta.internal.jpc.JavaDiagnosticProvider
import scala.meta.internal.jpc.JavaMetalsCompiler
import scala.meta.internal.jsemanticdb.Semanticdb
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.CompilerVirtualFileParams
import scala.meta.internal.metals.Configs.JavaSymbolLoaderConfig
import scala.meta.internal.metals.Embedded
import scala.meta.internal.metals.EmptyWorkDoneProgress
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.PositionSyntax._
import scala.meta.internal.metals.ReportLevel
import scala.meta.internal.metals.mbt.MbtV2WorkspaceSymbolSearch
import scala.meta.internal.pc.EmptySymbolSearch
import scala.meta.internal.pc.PresentationCompilerConfigImpl
import scala.meta.pc.ProgressBars

import munit.AnyFixture
import org.eclipse.{lsp4j => l}
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tests.FileLayout

abstract class BaseJavaPruneCompilerSuite extends munit.FunSuite {
  val tmp = new tests.TemporaryDirectoryFixture()
  override def munitFixtures: Seq[AnyFixture[_]] = List(tmp)

  val logger: Logger = LoggerFactory.getLogger(classOf[JavaPruneCompilerSuite])
  def compileFilesWithFormattedDiagnostics(
      layout: String,
      mainPath: String,
  ): String = {
    val result = compileFiles(layout, mainPath)
    result.diagnostics
      .map(d => d.formatMessage(result.input))
      .mkString("\n")
  }

  case class CompileFilesResult(
      diagnostics: List[l.Diagnostic],
      input: Input.VirtualFile,
  )
  def languages: Set[Semanticdb.Language] =
    Set(Semanticdb.Language.JAVA, Semanticdb.Language.PROTOBUF)
  def compileFiles(
      layout: String,
      mainPath: String,
  ): CompileFilesResult = {
    val main = Paths.get(mainPath)
    if (main.isAbsolute) {
      throw new IllegalArgumentException(
        s"mainPath must be relative: $mainPath"
      )
    }
    val embedded = new Embedded(tmp(), EmptyWorkDoneProgress)
    val mbt = new MbtV2WorkspaceSymbolSearch(
      workspace = tmp(),
      javaSymbolLoader = () => JavaSymbolLoaderConfig.javacSourcepath,
    )
    val dir = FileLayout.fromString(layout, root = tmp())
    mbt.onReindex().awaitBackgroundJobs()
    val uri = dir.resolve(mainPath).toNIO.toUri
    val text = dir.resolve(mainPath).toInputFromBuffers(Buffers()).text
    val input =
      dir.resolve(mainPath).toInputFromBuffers(Buffers()).copy(path = mainPath)
    val global = new JavaMetalsCompiler(
      buildTargetId = "",
      logger = LoggerFactory.getLogger(classOf[JavaMetalsCompiler]),
      reportsLevel = ReportLevel.Info,
      search = EmptySymbolSearch,
      embedded = embedded,
      javaFileManagerFactory = mbt,
      metalsConfig = PresentationCompilerConfigImpl(),
      classpath = Nil,
      options = Nil,
      progressBars = ProgressBars.EMPTY,
    )
    val params = CompilerVirtualFileParams(uri, text)
    val diagnosticProvider = new JavaDiagnosticProvider(global, params)
    val diagnostics = diagnosticProvider.diagnostics()
    CompileFilesResult(diagnostics, input)
  }

  def checkNoErrors(
      name: munit.TestOptions,
      layout: String,
      mainPath: String,
  )(implicit loc: munit.Location): Unit = {
    test(name) {
      val withPlugin = compileFilesWithFormattedDiagnostics(layout, mainPath)
      assertNoDiff(withPlugin, "")
    }
  }

  def checkErrors(
      name: munit.TestOptions,
      layout: String,
      mainPath: String,
      expectedDiagnostics: String,
  )(implicit loc: munit.Location): Unit = {
    test(name) {
      assertNoDiff(
        compileFilesWithFormattedDiagnostics(layout, mainPath),
        expectedDiagnostics,
      )
    }
  }
}
