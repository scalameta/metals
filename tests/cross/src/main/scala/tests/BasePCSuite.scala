package tests

import coursier._
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.jsonrpc.messages.{Either => JEither}
import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.ClasspathSearch
import scala.meta.internal.metals.JdkSources
import scala.meta.internal.metals.Docstrings
import scala.meta.internal.metals.RecursivelyDelete
import scala.meta.internal.mtags.BuildInfo
import scala.meta.internal.mtags.OnDemandSymbolIndex
import scala.meta.internal.mtags.ClasspathLoader
import scala.meta.internal.pc.PresentationCompilerConfigImpl
import scala.meta.internal.pc.ScalaPresentationCompiler
import scala.meta.internal.metals.PackageIndex
import scala.meta.io.AbsolutePath
import scala.meta.pc.PresentationCompilerConfig
import scala.util.control.NonFatal
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import org.eclipse.{lsp4j => l}
import scala.collection.Seq
import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.metals.TextEdits
import scala.meta.pc.PresentationCompiler

abstract class BasePCSuite extends BaseSuite {
  def thisClasspath: Seq[Path] =
    ClasspathLoader
      .getURLs(this.getClass.getClassLoader)
      .map(url => Paths.get(url.toURI))
  val scalaLibrary: Seq[Path] = PackageIndex.scalaLibrary
  def extraClasspath: Seq[Path] = Nil
  def scalacOptions: Seq[String] = Nil
  def config: PresentationCompilerConfig =
    PresentationCompilerConfigImpl().copy(
      snippetAutoIndent = false
    )
  val myclasspath: Seq[Path] = extraClasspath ++ scalaLibrary.toList
  val index = new DelegatingGlobalSymbolIndex(OnDemandSymbolIndex())
  val indexer = new Docstrings(index)
  val workspace = new TestingWorkspaceSearch
  val search = new TestingSymbolSearch(
    ClasspathSearch.fromClasspath(myclasspath),
    new Docstrings(index),
    workspace,
    index
  )
  val executorService: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor()
  val pc: PresentationCompiler = new ScalaPresentationCompiler()
    .withSearch(search)
    .withConfiguration(config)
    .withExecutorService(executorService)
    .withScheduledExecutorService(executorService)
    .newInstance("", myclasspath.asJava, scalacOptions.asJava)
  val tmp: AbsolutePath = AbsolutePath(Files.createTempDirectory("metals"))
  override def utestAfterAll(): Unit = {
    executorService.shutdown()
  }

  def requiresJdkSources: Boolean = false

  def indexJDK(): Unit = {
    JdkSources().foreach(jdk => index.addSourceJar(jdk))
  }

  override def test(name: String)(fun: => Any): Unit = {
    // We are unable to infer the JDK jars on Appveyor
    // tests.BasePCSuite.indexJDK(BasePCSuite.scala:44)
    val testName =
      if (isCI && BuildInfo.scalaCompilerVersion != BuildInfoVersions.scala212)
        s"${BuildInfo.scalaCompilerVersion}-$name"
      else name
    super.test(testName) {
      try {
        fun
      } catch {
        case NonFatal(e)
            if e.getMessage != null &&
              e.getMessage.contains("x$1") &&
              !hasJdkSources =>
          // ignore failing test if jdk sources are missing
          ()
      }
    }
  }
  def indexScalaLibrary(): Unit = {
    val sources = Fetch()
      .addClassifiers(Classifier.sources)
      .addDependencies(
        Dependency(
          mod"org.scala-lang:scala-library",
          // NOTE(gabro): we should ideally just use BuildoInfoVersions.scalaVersion
          // but using the 2.11 stdlib would cause a lot tests to break for little benefit.
          // We can remove this switch once we drop support for 2.11
          BuildInfoVersions.scalaVersion match {
            case v if v.startsWith("2.13") => v
            case v if v.startsWith("2.12") => v
            case _ => BuildInfoVersions.scala212
          }
        )
      )
      .run()
    sources.foreach { jar =>
      index.addSourceJar(AbsolutePath(jar))
    }
  }

  override def afterAll(): Unit = {
    pc.shutdown()
    RecursivelyDelete(tmp)
  }
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
  def sortLines(stableOrder: Boolean, string: String): String =
    if (stableOrder) string
    else string.linesIterator.toList.sorted.mkString("\n")

  def prepareDefinition(
      original: String,
      uri: String
  ): (String, Int, l.Range) = {
    import scala.meta.inputs.Position
    import scala.meta.inputs.Input
    import scala.meta.internal.mtags.MtagsEnrichments._

    val (code, offset) = params(
      original
        .replaceAllLiterally("<<", "")
        .replaceAllLiterally(">>", ""),
      uri
    )
    val offsetRange = Position.Range(Input.String(code), offset, offset).toLSP
    (code, offset, offsetRange)
  }

  def locationsToCode(
      code: String,
      uri: String,
      offsetRange: l.Range,
      locations: java.util.List[l.Location]
  ): String = {
    val edits = locations.asScala.toList.flatMap { location =>
      if (location.getUri == uri) {
        List(
          new l.TextEdit(
            new l.Range(
              location.getRange.getStart,
              location.getRange.getStart
            ),
            "<<"
          ),
          new l.TextEdit(
            new l.Range(
              location.getRange.getEnd,
              location.getRange.getEnd
            ),
            ">>"
          )
        )
      } else {
        val filename = location.getUri
        val comment = s"/*$filename*/"
        if (code.contains(comment)) {
          Nil
        } else {
          List(new l.TextEdit(offsetRange, comment))
        }
      }
    }
    TextEdits.applyEdits(code, edits)
  }

  def obtainedAndExpected(
      fun: CompilerOffsetParams => CompletableFuture[
        java.util.List[l.Location]
      ]
  )(original: String, uri: String): (String, String) = {
    val (code, offset, offsetRange) = prepareDefinition(original, uri)
    val locations = fun(CompilerOffsetParams(uri, code, offset)).get()
    val obtained = locationsToCode(code, uri, offsetRange, locations)
    val expected = original.replaceAllLiterally("@@", "")
    (obtained, expected)
  }
}
