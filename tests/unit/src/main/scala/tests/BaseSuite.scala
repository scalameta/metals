package tests

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.language.experimental.macros
import scala.reflect.ClassTag
import utest.TestSuite
import utest.Tests
import utest.asserts.Asserts
import utest.framework.Formatter
import utest.framework.TestCallTree
import utest.framework.Tree
import utest.ufansi.Str
import scala.meta.internal.metals.MetalsLogger
import scala.meta.io.AbsolutePath
import utest.ufansi.Attrs

/**
 * Test suite that replace utest DSL with FunSuite-style syntax from ScalaTest.
 *
 * Exposes
 *
 */
class BaseSuite extends TestSuite {
  MetalsLogger.updateDefaultFormat()
  def isAppveyor: Boolean = "True" == System.getenv("APPVEYOR")
  def beforeAll(): Unit = ()
  def afterAll(): Unit = ()
  def intercept[T: ClassTag](exprs: Unit): T = macro Asserts.interceptProxy[T]
  def assertContains(string: String, substring: String): Unit = {
    assert(string.contains(substring))
  }
  def assertNotContains(string: String, substring: String): Unit = {
    assert(!string.contains(substring))
  }
  def assert(exprs: Boolean*): Unit = macro Asserts.assertProxy
  def assertNotEquals[T](obtained: T, expected: T, hint: String = ""): Unit = {
    if (obtained == expected) {
      val hintMsg = if (hint.isEmpty) "" else s" (hint: $hint)"
      assertNoDiff(obtained.toString, expected.toString, hint)
      fail(s"obtained=<$obtained> == expected=<$expected>$hintMsg")
    }
  }
  def assertEquals[T](obtained: T, expected: T, hint: String = ""): Unit = {
    if (obtained != expected) {
      val hintMsg = if (hint.isEmpty) "" else s" (hint: $hint)"
      assertNoDiff(obtained.toString, expected.toString, hint)
      fail(s"obtained=<$obtained> != expected=<$expected>$hintMsg")
    }
  }
  def assertNotFile(path: AbsolutePath): Unit = {
    if (path.isFile) {
      fail(s"file exists: $path", stackBump = 1)
    }
  }
  def assertIsFile(path: AbsolutePath): Unit = {
    if (!path.isFile) {
      fail(s"no such file: $path", stackBump = 1)
    }
  }
  def assertNoDiff(
      obtained: String,
      expected: String,
      title: String = ""
  )(implicit filename: sourcecode.File, line: sourcecode.Line): Unit = {
    DiffAssertions.colored {
      DiffAssertions.assertNoDiffOrPrintExpected(obtained, expected, title)
    }
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
  case class FlatTest(name: String, thunk: () => Unit)
  private val myTests = IndexedSeq.newBuilder[FlatTest]

  def ignore(name: String)(fun: => Any): Unit = {
    myTests += FlatTest(
      utest.ufansi.Color.LightRed(s"IGNORED - $name").toString(),
      () => ()
    )
  }
  def test(name: String)(fun: => Any): Unit = {
    myTests += FlatTest(name, () => fun)
  }
  def testAsync(name: String, maxDuration: Duration = Duration("3min"))(
      run: => Future[Unit]
  ): Unit = {
    test(name) {
      val fut = run
      Await.result(fut, maxDuration)
    }
  }

  def fail(msg: String, stackBump: Int = 0): Nothing = {
    val ex = new TestFailedException(msg)
    ex.setStackTrace(ex.getStackTrace.slice(1 + stackBump, 2 + stackBump))
    throw ex
  }

  override def tests: Tests = {
    val ts = myTests.result()
    val names = Tree("", ts.map(x => Tree(x.name)): _*)
    val thunks = new TestCallTree({
      this.beforeAll()
      Right(ts.map(x => new TestCallTree(Left(x.thunk()))))
    })
    Tests(names, thunks)
  }
}
