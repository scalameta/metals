package tests.j

import java.net.URI

import scala.meta.internal.jpc.JavaPruneCompiler
import scala.meta.internal.metals.CompilerVirtualFileParams
import scala.meta.internal.metals.Embedded
import scala.meta.internal.metals.EmptyWorkDoneProgress
import scala.meta.internal.metals.ReportLevel
import scala.meta.internal.metals.config.JavacServicesOverrides
import scala.meta.pc.JavaFileManagerFactory
import scala.meta.pc.ProgressBars

import com.sun.tools.javac.api.JavacTaskImpl
import com.sun.tools.javac.util.Names
import org.slf4j.LoggerFactory

class JavaCompilerNamesSuite extends munit.FunSuite {
  private val tmp = new tests.TemporaryDirectoryFixture()
  override def munitFixtures: Seq[munit.AnyFixture[_]] = List(tmp)

  private def newCompiler(reuseNames: Boolean = true): JavaPruneCompiler =
    new JavaPruneCompiler(
      logger = LoggerFactory.getLogger(classOf[JavaCompilerNamesSuite]),
      reportsLevel = ReportLevel.Info,
      javaFileManagerFactory = JavaFileManagerFactory.EMPTY,
      embedded = new Embedded(tmp(), EmptyWorkDoneProgress),
      progressBars = ProgressBars.EMPTY,
      servicesOverrides =
        JavacServicesOverrides.default.copy(names = reuseNames),
    )

  private def names(compiler: JavaPruneCompiler, id: Int): Names = {
    val result = compiler.compileTask(
      CompilerVirtualFileParams(
        URI.create(s"file:///Example$id.java"),
        s"class Example$id {}",
      )
    )
    Names.instance(result.task.asInstanceOf[JavacTaskImpl].getContext())
  }

  test("different-compilers") {
    val first = newCompiler()
    val second = newCompiler()
    try {
      val firstNames = names(first, 1)
      val secondNames = names(second, 2)
      assertEquals(firstNames eq secondNames, false)
      assertEquals(firstNames.table eq secondNames.table, false)
    } finally {
      first.close()
      second.close()
    }
  }

  test("successive-tasks") {
    val compiler = newCompiler()
    try {
      val first = names(compiler, 1)
      val identifier = first.fromString("WorkspaceEntity")
      val second = names(compiler, 2)
      assert(first eq second)
      assert(first eq names(compiler, 1))
      assertEquals(identifier.toString, "WorkspaceEntity")
    } finally compiler.close()
  }

  test("compiler-replacement") {
    val compiler = newCompiler()
    val previous =
      try names(compiler, 1)
      finally compiler.close()
    val identifier = previous.fromString("WorkspaceEntity")
    val replacement = newCompiler()
    try {
      val current = names(replacement, 2)
      assertEquals(previous eq current, false)
      assertEquals(previous.table eq current.table, false)
      assertEquals(identifier.toString, "WorkspaceEntity")
    } finally replacement.close()
  }

  test("disabled-names-override") {
    val first = newCompiler(reuseNames = false)
    val second = newCompiler(reuseNames = false)
    try {
      val firstNames = names(first, 1)
      val secondNames = names(first, 2)
      val thirdNames = names(second, 3)
      assert(firstNames ne secondNames)
      assert(firstNames ne thirdNames)
      assert(firstNames.table ne secondNames.table)
      assert(firstNames.table ne thirdNames.table)
    } finally {
      first.close()
      second.close()
    }
  }
}
