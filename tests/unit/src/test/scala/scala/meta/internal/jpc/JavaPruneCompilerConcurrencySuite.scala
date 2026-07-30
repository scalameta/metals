package scala.meta.internal.jpc

import scala.meta.internal.metals.Configs.JavacServicesOverrides
import scala.meta.internal.metals.Embedded
import scala.meta.internal.metals.ReportLevel
import scala.meta.pc.JavaFileManagerFactory
import scala.meta.pc.ProgressBars

import munit.AnyFixture
import org.slf4j.LoggerFactory

class JavaPruneCompilerConcurrencySuite extends munit.FunSuite {
  private val tmp = new tests.TemporaryDirectoryFixture()
  override def munitFixtures: Seq[AnyFixture[_]] = List(tmp)

  test("names-table-is-scoped-to-compiler") {
    val embedded = new Embedded(tmp())
    val first = newCompiler(embedded)
    val second = newCompiler(embedded)
    try {
      assertNotEquals(
        first.namesTable,
        second.namesTable,
        "Concurrent Java presentation compilers must not share javac Names",
      )
    } finally {
      first.close()
      second.close()
    }
  }

  private def newCompiler(embedded: Embedded): JavaPruneCompiler =
    new JavaPruneCompiler(
      logger =
        LoggerFactory.getLogger(classOf[JavaPruneCompilerConcurrencySuite]),
      reportsLevel = ReportLevel.Info,
      javaFileManagerFactory = JavaFileManagerFactory.EMPTY,
      embedded = embedded,
      progressBars = ProgressBars.EMPTY,
      servicesOverrides = JavacServicesOverrides.default,
    )
}
