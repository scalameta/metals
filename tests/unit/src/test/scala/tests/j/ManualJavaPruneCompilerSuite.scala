package tests.j

import java.nio.charset.StandardCharsets

import scala.concurrent.ExecutionContext.Implicits.global

import scala.meta.internal.jpc.JavaPruneCompiler
import scala.meta.internal.metals.CompilerVirtualFileParams
import scala.meta.internal.metals.Configs.WorkspaceSymbolProviderConfig
import scala.meta.internal.metals.Embedded
import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ReportLevel
import scala.meta.internal.metals.mbt.MbtV2WorkspaceSymbolSearch
import scala.meta.io.AbsolutePath
import scala.meta.pc.JavacServicesOverridesConfig
import scala.meta.pc.ProgressBars

import org.slf4j.LoggerFactory

@munit.IgnoreSuite
class ManualJavaPruneCompilerSuite extends munit.FunSuite {
  test("trino") {
    val root = AbsolutePath(System.getProperty("user.home")).resolve("trino")
    val embedded = new Embedded(root)
    val mbt = new MbtV2WorkspaceSymbolSearch(
      root,
      config = () => WorkspaceSymbolProviderConfig.mbt2,
    )
    mbt.onReindex().backgroundJobs.asJava.get()
    val pruneCompiler = new JavaPruneCompiler(
      LoggerFactory.getLogger(classOf[ManualJavaPruneCompilerSuite]),
      ReportLevel.Debug,
      semanticdbFileManager = mbt,
      embedded = embedded,
      progressBars = ProgressBars.EMPTY,
      servicesOverrides = JavacServicesOverridesConfig.EMPTY,
    )

    val path = root.resolve(
      "client/trino-jdbc/src/test/java/io/trino/jdbc/TestJdbcExternalAuthentication.java"
    )
    val params = CompilerVirtualFileParams(
      path.toNIO.toUri(),
      path.readText(StandardCharsets.UTF_8),
      EmptyCancelToken,
    )
    pruneCompiler.compileTask(params).withAnalyzePhase()
  }
}
