package tests

import scala.language.experimental.macros

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.meta.Lit
import scala.meta.languageserver.BuildInfo
import scala.reflect.ClassTag
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigUtil
import com.typesafe.config.ConfigValueFactory
import utest.TestSuite
import utest.Tests
import utest.asserts.Asserts
import utest.framework.Formatter
import utest.framework.TestCallTree
import utest.framework.Tree
import utest.ufansi.Str

/**
 * Test suite that supports
 *
 * - snapshot testing
 * - beforeAll
 * - afterAll
 * - pretty multiline string diffing
 * - FunSuite-style test("name") { => fun }
 */
class MegaSuite(implicit filename: sourcecode.File) extends TestSuite {
  def beforeAll(): Unit = ()
  def afterAll(): Unit = ()
  def intercept[T: ClassTag](exprs: Unit): T = macro Asserts.interceptProxy[T]
  def assert(exprs: Boolean*): Unit = macro Asserts.assertProxy
  def assertNoDiff(
      obtained: String,
      expected: String,
      title: String = ""
  ): Boolean = {
    DiffAsserts.assertNoDiff(obtained, expected, title)
  }
  override def utestAfterAll(): Unit = afterAll()

  override def utestFormatter: Formatter = new Formatter {
    override def exceptionStackFrameHighlighter(
        s: StackTraceElement
    ): Boolean = {
      s.getClassName.startsWith("scala.meta.languageserver.") ||
      s.getClassName.contains("tests")
    }
    override def formatException(x: Throwable, leftIndent: String): Str =
      super.formatException(x, "")
  }
  private val myTests = IndexedSeq.newBuilder[(String, () => Unit)]

  def ignore(name: String)(fun: => Any): Unit = {
    myTests += (name -> (() => ()))
  }
  def test(name: String)(fun: => Any): Unit = {
    myTests += (name -> (() => fun))
  }

  private class TestFailedException(msg: String) extends Exception(msg)
  def fail(msg: String) = {
    val ex = new TestFailedException(msg)
    ex.setStackTrace(ex.getStackTrace.slice(1, 2))
    throw ex
  }

  override def tests: Tests = {
    val ts = myTests.result()
    val names = Tree("", ts.map(x => Tree(x._1)): _*)
    val thunks = new TestCallTree({
      this.beforeAll()
      Right(ts.map(x => new TestCallTree(Left(x._2()))))
    })
    Tests.apply(names, thunks)
  }
}
