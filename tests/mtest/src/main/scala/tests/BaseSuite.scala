package tests

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.language.experimental.macros
import scala.meta.io.AbsolutePath
import scala.meta.internal.mtags
import scala.reflect.ClassTag
import scala.util.Properties
import utest.TestSuite
import utest.Tests
import utest.asserts.Asserts
import utest.framework.Formatter
import utest.framework.TestCallTree
import utest.framework.Tree
import utest.ufansi.Attrs
import utest.ufansi.Str
import scala.meta.internal.metals.JdkSources
import scala.meta.internal.metals.Testing
import scala.collection.mutable

/**
 * Test suite that replace utest DSL with FunSuite-style syntax from ScalaTest.
 */
class BaseSuite extends TestSuite {
  Testing.enable()
  def isJava8: Boolean =
    !Properties.isJavaAtLeast("9")
  def isScala211: Boolean =
    mtags.BuildInfo.scalaCompilerVersion.startsWith("2.11")
  def hasJdkSources: Boolean = JdkSources().isDefined
  def isWindows: Boolean =
    Properties.isWin
  def isCI: Boolean =
    "true".equalsIgnoreCase(System.getenv("CI"))
  def beforeAll(): Unit = ()
  def afterAll(): Unit = ()
  def intercept[T: ClassTag](exprs: Unit): T = macro Asserts.interceptProxy[T]
  def assertNotEmpty(string: String): Unit = {
    if (string.isEmpty) {
      fail(
        s"expected non-empty string, obtained empty string.",
        stackBump = 1
      )
    }
  }
  def assertEmpty(string: String): Unit = {
    if (!string.isEmpty) {
      fail(
        s"expected empty string, obtained: $string",
        stackBump = 1
      )
    }
  }
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
  def assertIsNotDirectory(path: AbsolutePath): Unit = {
    if (path.isDirectory) {
      fail(s"directory exists: $path", stackBump = 1)
    }
  }
  def assertNoDiff(
      obtained: String,
      expected: String,
      title: String = ""
  )(implicit filename: sourcecode.File, line: sourcecode.Line): Unit = {
    DiffAssertions.colored {
      DiffAssertions.assertNoDiffOrPrintObtained(
        obtained,
        expected,
        title,
        title
      )
    }
  }
  override def utestAfterAll(): Unit = afterAll()

  override def utestFormatter: Formatter = new Formatter {
    override def exceptionMsgColor: Attrs = Attrs.Empty
    override def exceptionStackFrameHighlighter(
        s: StackTraceElement
    ): Boolean = {
      s.getClassName.startsWith("scala.meta.internal.worksheets.") ||
      s.getClassName.startsWith("scala.meta.internal.tvp.") ||
      s.getClassName.startsWith("scala.meta.internal.pc.") ||
      s.getClassName.startsWith("scala.meta.internal.mtags.") ||
      s.getClassName.startsWith("scala.meta.internal.metals.") ||
      s.getClassName.startsWith("scala.meta.metals.") ||
      s.getClassName.startsWith("scala.meta.pc.") ||
      (s.getClassName.startsWith("tests") &&
      !s.getClassName.startsWith("tests.DiffAssertions") &&
      !s.getClassName.startsWith("tests.MegaSuite"))
    }
    override def formatWrapWidth: Int = 3000
    override def formatException(x: Throwable, leftIndent: String): Str =
      super.formatException(x, "")
  }
  case class FlatTest(name: String, thunk: () => Unit)
  private val myTests = mutable.ArrayBuffer.empty[FlatTest]

  def ignore(name: String)(fun: => Any): Unit = {
    myTests += FlatTest(
      utest.ufansi.Color.LightRed(s"IGNORED - $name").toString(),
      () => ()
    )
  }

  def isTestSuiteEnabled: Boolean = true

  def test(name: String)(fun: => Any): Unit = {
    if (isTestSuiteEnabled) {
      myTests += FlatTest(name, () => fun)
    }
  }
  def testAsync(name: String, maxDuration: Duration = Duration("10min"))(
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

  lazy val tests: Tests = {
    if (myTests.isEmpty) {
      myTests += FlatTest("empty", () => ())
    }
    this.beforeAll()
    val names = Tree("", myTests.map(x => Tree(x.name)).toSeq: _*)
    val inner = Right(
      myTests.map(x => new TestCallTree(Left(x.thunk()))).toIndexedSeq
    )
    val thunks = new TestCallTree(inner)
    Tests(names, thunks)
  }

  private def scalaVersion: String =
    Properties.versionNumberString
  private def scalaBinary(scalaVersion: String): String =
    scalaVersion.split("\\.").take(2).mkString(".")
  val compatProcess: Map[String, String => String] =
    Map.empty[String, String => String]

  def getExpected(
      default: String,
      compat: Map[String, String],
      scalaVersion: String = this.scalaVersion
  ): String = {
    val postProcess = compatProcess
      .get(scalaBinary(scalaVersion))
      .orElse(compatProcess.get(scalaVersion))
      .getOrElse(identity[String] _)
    val result = compat
      .get(scalaBinary(scalaVersion))
      .orElse(compat.get(scalaVersion))
      .getOrElse(default)
    postProcess(result)
  }
}
