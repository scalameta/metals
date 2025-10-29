package tests.index

import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.EmptyReportContext
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.PositionSyntax.XtensionPositionsScalafix
import scala.meta.internal.mtags.ClasspathDefinitionIndex
import scala.meta.internal.mtags.Mtags
import scala.meta.internal.mtags.Symbol

import munit.AnyFixture
import tests.Library
import tests.LibraryFixture
class ClasspathDefinitionIndexSuite extends munit.FunSuite {
  val xnio = new LibraryFixture("xnio", () => Library.xnio)
  val xnio2 = new LibraryFixture("xnio2", () => Library.xnio2)
  val scala2 = new LibraryFixture("scala2", () => Library.allScala2Library)
  override def munitFixtures: Seq[AnyFixture[_]] = List(xnio, xnio2, scala2)
  def defaultLibs: Library = xnio() ++ scala2()
  val buffers: Buffers = Buffers()

  def checkDefinition(
      name: munit.TestOptions,
      symbol: String,
      expected: String,
      libs: () => Library = () => defaultLibs,
  )(implicit loc: munit.Location): Unit = {
    test(name) {
      val mtags = new Mtags()(EmptyReportContext)
      val index = new ClasspathDefinitionIndex(mtags)
      libs().asModules.foreach(module => index.addDependencyModule(module))

      val defns = index.definitions(Symbol(symbol))
      val obtained = defns.map { case (module, d) =>
        val Array(_, jarpath) = d.path.toURI.toString().split("!")
        val input = d.path.toInputFromBuffers(buffers).copy(path = jarpath)
        val range = d.range.get
        val pos = input.toPosition(range.startLine, range.startCharacter)
        pos.formatMessage("", module.coordinates.syntax, noPos = true)
      }
      assertNoDiff(obtained.sorted.mkString("\n"), expected)
    }
  }

  checkDefinition(
    "java-easy",
    "org/jboss/threads/JBossExecutors#THREAD_ERROR_LOGGER.",
    """|/org/jboss/threads/JBossExecutors.java org.jboss.threads:jboss-threads:2.3.6.Final
       |    private static final Logger THREAD_ERROR_LOGGER = Logger.getLogger("org.jboss.threads.errors");
       |                                ^
       |""".stripMargin,
  )

  checkDefinition(
    "ambiguous",
    "org/xnio/nio/SelectorUtils#",
    """|/org/xnio/nio/SelectorUtils.java org.jboss.xnio:xnio-nio:3.8.17.Final
       |final class SelectorUtils {
       |            ^
       |/org/xnio/nio/SelectorUtils.java org.jboss.xnio:xnio-nio:3.8.8.Final
       |final class SelectorUtils {
       |            ^
       |""".stripMargin,
    libs = () => xnio() ++ xnio2(),
  )

  checkDefinition(
    "scala-hard",
    // This test covers both 1) mismatching filename against toplevel symbol and
    // 2) synthetic symbol falling back to a non-synthetic symbol
    "scala/util/Left#copy().(value)",
    """|/scala/util/Either.scala org.scala-lang:scala-library:2.13.16
       |final case class Left[+A, +B](value: A) extends Either[A, B] {
       |                              ^
       |""".stripMargin,
  )
}
