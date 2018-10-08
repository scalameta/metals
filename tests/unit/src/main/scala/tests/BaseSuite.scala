package tests

import scala.language.experimental.macros
import scala.reflect.ClassTag
import utest.TestSuite
import utest.Tests
import utest.asserts.Asserts
import utest.framework.Formatter
import utest.framework.TestCallTree
import utest.framework.Tree
import utest.ufansi.Str
import io.circe.Json
import io.circe.Printer
import scala.meta.metals.MetalsLogger
import scala.util.control.NonFatal
import utest.ufansi.Attrs
import utest.ufansi.Color

/**
 * Test suite that replace utest DSL with FunSuite-style syntax from ScalaTest.
 *
 * Exposes
 *
 */
class BaseSuite extends TestSuite {
  scribe.Logger.root
    .clearHandlers()
    .withHandler(
      formatter = MetalsLogger.defaultFormat,
      minimumLevel = Some(scribe.Level.Info)
    )
    .replace()
  private val jsonPrinter: Printer = Printer.spaces2.copy(dropNullValues = true)
  def beforeAll(): Unit = ()
  def afterAll(): Unit = ()
  def intercept[T: ClassTag](exprs: Unit): T = macro Asserts.interceptProxy[T]
  def assert(exprs: Boolean*): Unit = macro Asserts.assertProxy
  def assertEquals[T](obtained: T, expected: T, hint: String = ""): Unit = {
    if (obtained != expected) {
      val hintMsg = if (hint.isEmpty) "" else s" (hint: $hint)"
      assertNoDiff(obtained.toString, expected.toString, hint)
      fail(s"obtained=<$obtained> != expected=<$expected>$hintMsg")
    }
  }
  def assertNoDiff(
      obtained: String,
      expected: String,
      title: String = ""
  ): Unit = {
    DiffAssertions.colored {
      DiffAssertions.assertNoDiffOrPrintExpected(obtained, expected, title)
    }
  }
  def assertNoDiff(
      obtained: Json,
      expected: String
  ): Unit = {
    assertNoDiff(obtained.pretty(jsonPrinter), expected)
  }
  override def utestAfterAll(): Unit = afterAll()

  override def utestFormatter: Formatter = new Formatter {
    override def exceptionMsgColor: Attrs = Attrs.Empty
    override def exceptionStackFrameHighlighter(
        s: StackTraceElement
    ): Boolean = {
      s.getClassName.startsWith("scala.meta.internal.mtags.") ||
      s.getClassName.startsWith("scala.meta.internal.metals.") ||
      s.getClassName.startsWith("scala.meta.metals.") ||
      (s.getClassName.startsWith("tests") &&
      !s.getClassName.startsWith("tests.DiffAssertions") &&
      !s.getClassName.startsWith("tests.MegaSuite"))
    }
    override def formatWrapWidth: Int = 3000
    override def formatException(x: Throwable, leftIndent: String): Str =
      super.formatException(x, "")
  }
  private val myTests = IndexedSeq.newBuilder[(String, () => Unit)]

  def ignore(name: String)(fun: => Any): Unit = {
    myTests += (utest.ufansi.Color.LightRed(s"IGNORED - $name").toString() -> (
        () => ()
    ))
  }
  def test(name: String)(fun: => Any): Unit = {
    myTests += (name -> (() => fun))
  }

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
class TestFailedException(msg: String) extends Exception(msg)
