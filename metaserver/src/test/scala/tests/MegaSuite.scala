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

/**
 * Test suite that supports
 *
 * - beforeAll
 * - afterAll
 * - pretty multiline string diffing
 * - FunSuite-style test("name") { => fun }
 */
class MegaSuite extends TestSuite {
  private val jsonPrinter: Printer = Printer.spaces2.copy(dropNullValues = true)
  def beforeAll(): Unit = ()
  def afterAll(): Unit = ()
  def intercept[T: ClassTag](exprs: Unit): T = macro Asserts.interceptProxy[T]
  def assert(exprs: Boolean*): Unit = macro Asserts.assertProxy
  def assertEquals[T](obtained: T, expected: T, hint: String = ""): Unit = {
    if (obtained != expected) {
      val hintMsg = if (hint.isEmpty) "" else s" (hint: $hint)"
      // TODO(olafur) handle sequences
      val diff =
        DiffAsserts.error2message(obtained.toString, expected.toString)
      if (diff.isEmpty)
        fail(s"obtained=<$obtained> != expected=<$expected>$hintMsg")
      else fail(diff + hintMsg)
    }
  }
  def assertNoDiff(
      obtained: String,
      expected: String,
      title: String = ""
  ): Unit = {
    DiffAsserts.assertNoDiff(obtained, expected, title)
  }
  def assertNoDiff(
      obtained: Json,
      expected: String
  ): Unit = {
    assertNoDiff(obtained.pretty(jsonPrinter), expected)
  }
  override def utestAfterAll(): Unit = afterAll()

  override def utestFormatter: Formatter = new Formatter {
    override def exceptionStackFrameHighlighter(
        s: StackTraceElement
    ): Boolean = {
      s.getClassName.startsWith("scala.meta.languageserver.") ||
      (s.getClassName.startsWith("tests") &&
      !s.getClassName.startsWith("tests.DiffAsserts") &&
      !s.getClassName.startsWith("tests.MegaSuite"))
    }
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
