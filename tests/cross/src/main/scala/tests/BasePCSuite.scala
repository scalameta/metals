package tests

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.nio.charset.StandardCharsets
import coursierapi.Dependency
import coursierapi.Fetch
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.jsonrpc.messages.{Either => JEither}
import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.ClasspathSearch
import scala.meta.internal.metals.JdkSources
import scala.meta.internal.metals.Docstrings
import scala.meta.internal.metals.RecursivelyDelete
import scala.meta.internal.pc.PresentationCompilerConfigImpl
import scala.meta.internal.mtags.GlobalSymbolIndex
import scala.meta.io.AbsolutePath
import scala.meta.pc.PresentationCompilerConfig
import scala.collection.Seq
import scala.util.control.NonFatal
import munit.TestOptions
import scala.meta.pc.PresentationCompiler
import scala.meta.internal.pc.ScalaPresentationCompiler

abstract class BasePCSuite extends BaseSuite {

  val executorService: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor()

  val scalaVersion = BuildInfoVersions.scalaVersion

  def config: PresentationCompilerConfig =
    PresentationCompilerConfigImpl().copy(
      snippetAutoIndent = false
    )

  def extraDependencies(scalaVersion: String): Seq[Dependency] = Nil

  def scalacOptions(classpath: Seq[Path]): Seq[String] = Nil

  def excludedScalaVersions: Set[String] = Set.empty

  protected val index = new DelegatingGlobalSymbolIndex()

  protected val workspace = new TestingWorkspaceSearch

  private def indexScalaLibrary(
      index: GlobalSymbolIndex,
      scalaVersion: String
  ): Unit = {
    val sources = Fetch
      .create()
      .withClassifiers(Set("sources").asJava)
      .withDependencies(
        Dependency.of(
          "org.scala-lang",
          "scala-library",
          // NOTE(gabro): we should ideally just use BuildoInfoVersions.scalaVersion
          // but using the 2.11 stdlib would cause a lot tests to break for little benefit.
          // We can remove this switch once we drop support for 2.11
          scalaVersion match {
            case v if v.startsWith("2.13") => v
            case v if v.startsWith("2.12") => v
            case _ => BuildInfoVersions.scala212
          }
        )
      )
      .fetch()
      .asScala
    sources.foreach { jar => index.addSourceJar(AbsolutePath(jar)) }
  }

  protected lazy val presentationCompiler: PresentationCompiler = {
    val binaryVersion = ScalaDependencies.createBinaryVersion(scalaVersion)
    val scalaDependencies =
      if (binaryVersion.startsWith("0."))
        ScalaDependencies.scala3(scalaVersion)
      else
        ScalaDependencies.scala2(scalaVersion)

    val fetch = Fetch
      .create()

    val allDependencies = scalaDependencies ++ extraDependencies(scalaVersion)

    allDependencies.foreach(fetch.addDependencies(_))
    val libraries: Seq[Path] = fetch
      .fetch()
      .asScala
      .map(_.toPath())

    val myclasspath: Seq[Path] = libraries.filterNot { path =>
      val filename = path.toString()
      filename.contains("scala-reflect") ||
      filename.contains("scala-compiler") ||
      filename.contains("scala-xml")
    }

    if (requiresJdkSources)
      JdkSources().foreach(jdk => index.addSourceJar(jdk))
    if (requiresScalaLibrarySources)
      indexScalaLibrary(index, scalaVersion)
    val indexer = new Docstrings(index)
    val search = new TestingSymbolSearch(
      ClasspathSearch.fromClasspath(myclasspath),
      new Docstrings(index),
      workspace,
      index
    )

    val scalacOpts = scalacOptions(myclasspath)

    new ScalaPresentationCompiler()
      .withSearch(search)
      .withConfiguration(config)
      .withExecutorService(executorService)
      .withScheduledExecutorService(executorService)
      .newInstance("", myclasspath.asJava, scalacOpts.asJava)
  }

  val tmp: AbsolutePath = AbsolutePath(Files.createTempDirectory("metals"))

  override def afterAll(): Unit = {
    presentationCompiler.shutdown()
    RecursivelyDelete(tmp)
    executorService.shutdown()
  }

  def testPc(name: TestOptions, ignoredScalaVersions: Set[String] = Set.empty)(
      fn: PresentationCompiler => Unit
  ): Unit = {
    if (excludedScalaVersions(scalaVersion)) {
      test(name.ignore) {}
    } else {
      val testOptions: TestOptions =
        name.withName(s"${name.name}_$scalaVersion")
      val possiblyIgnored =
        if (ignoredScalaVersions(scalaVersion)) testOptions.ignore
        else testOptions
      test(possiblyIgnored) {
        fn(presentationCompiler)
      }
    }
  }

  def requiresJdkSources: Boolean = false
  def requiresScalaLibrarySources: Boolean = false

  def params(code: String, filename: String = "test.scala"): (String, Int) = {
    val code2 = code.replaceAllLiterally("@@", "")
    val offset = code.indexOf("@@")
    if (offset < 0) {
      fail("missing @@")
    }
    val file = tmp.resolve(filename)
    Files.write(file.toNIO, code2.getBytes(StandardCharsets.UTF_8))
    try index.addSourceFile(file, Some(tmp))
    catch {
      case NonFatal(e) =>
        println(s"warn: $e")
    }
    workspace.inputs(filename) = code2
    (code2, offset)
  }

  def doc(e: JEither[String, MarkupContent]): String = {
    if (e == null) ""
    else if (e.isLeft) {
      " " + e.getLeft
    } else {
      " " + e.getRight.getValue
    }
  }.trim

  def sortLines(stableOrder: Boolean, string: String): String = {
    if (stableOrder) string
    else string.linesIterator.toList.sorted.mkString("\n")
  }
}

object ScalaDependencies {

  def isScala3Version(scalaVersion: String): Boolean = {
    scalaVersion.startsWith("0.") || scalaVersion.startsWith("3.")
  }
  def createBinaryVersion(scalaVersion: String): String = {
    scalaVersion.split('.').take(2).mkString(".")
  }

  def scala3(scalaVersion: String): Seq[Dependency] = {
    val binaryVersion = createBinaryVersion(scalaVersion)
    Seq(
      Dependency.of("org.scala-lang", "scala-library", "2.13.1"),
      Dependency
        .of("ch.epfl.lamp", s"dotty-compiler_$binaryVersion", scalaVersion),
      Dependency.of("ch.epfl.lamp", s"tasty-core_$binaryVersion", scalaVersion),
      Dependency
        .of("ch.epfl.lamp", s"dotty-library_$binaryVersion", scalaVersion),
      Dependency.of("ch.epfl.lamp", "dotty-interfaces", scalaVersion)
    )
  }

  def scala2(scalaVersion: String): Seq[Dependency] = {
    Seq(
      Dependency.of("org.scala-lang", "scala-library", scalaVersion),
      Dependency.of("org.scala-lang", "scala-compiler", scalaVersion),
      Dependency.of("org.scala-lang", "scala-reflect", scalaVersion)
    )
  }
}
