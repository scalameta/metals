package tests

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.nio.charset.StandardCharsets
import coursierapi.Dependency
import coursierapi.Fetch
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
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
import scala.util.control.NonFatal
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import org.eclipse.{lsp4j => l}
import scala.collection.Seq
import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.metals.TextEdits
import scala.meta.pc.PresentationCompiler
import scala.meta.internal.pc.ScalaPresentationCompiler
import scala.meta.internal.metals.PackageIndex
import munit.Tag

abstract class BasePCSuite extends BaseSuite {

  val executorService: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor()
  val scalaVersion = BuildInfoVersions.scalaVersion
  protected val index = new DelegatingGlobalSymbolIndex()
  protected val workspace = new TestingWorkspaceSearch
  val tmp: AbsolutePath = AbsolutePath(Files.createTempDirectory("metals"))

  protected lazy val presentationCompiler: PresentationCompiler = {
    val scalaLibrary =
      if (isScala3Version(scalaVersion))
        PackageIndex.scalaLibrary ++ PackageIndex.dottyLibrary
      else
        PackageIndex.scalaLibrary

    val fetch = Fetch.create()

    extraDependencies(scalaVersion).foreach(fetch.addDependencies(_))
    val extraLibraries: Seq[Path] = fetch
      .fetch()
      .asScala
      .map(_.toPath())

    val myclasspath: Seq[Path] = extraLibraries ++ scalaLibrary

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

  protected def config: PresentationCompilerConfig =
    PresentationCompilerConfigImpl().copy(
      snippetAutoIndent = false
    )

  protected def extraDependencies(scalaVersion: String): Seq[Dependency] = Nil

  protected def scalacOptions(classpath: Seq[Path]): Seq[String] = Nil

  protected def excludedScalaVersions: Set[String] = Set.empty

  protected def requiresJdkSources: Boolean = false

  protected def requiresScalaLibrarySources: Boolean = false

  protected def isScala3Version(scalaVersion: String): Boolean = {
    scalaVersion.startsWith("0.") || scalaVersion.startsWith("3.")
  }

  protected def createBinaryVersion(scalaVersion: String): String = {
    scalaVersion.split('.').take(2).mkString(".")
  }

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
          // Additionally, when using Scala 3 the 2.13 Scala library is used.
          scalaVersion match {
            case v if v.startsWith("2.13") => v
            case v if isScala3Version(v) => BuildInfoVersions.scala213
            case v if v.startsWith("2.12") => v
            case _ => BuildInfoVersions.scala212
          }
        )
      )
      .fetch()
      .asScala
    sources.foreach { jar => index.addSourceJar(AbsolutePath(jar)) }
  }

  override def afterAll(): Unit = {
    presentationCompiler.shutdown()
    RecursivelyDelete(tmp)
    executorService.shutdown()
  }

  override def munitIgnore: Boolean = excludedScalaVersions(scalaVersion)

  override def munitTestTransforms: List[TestTransform] =
    super.munitTestTransforms ++ List(
      new TestTransform("Append Scala version", { test =>
        test.withName(test.name + "_" + scalaVersion)
      }),
      new TestTransform("Ignore Scala version", { test =>
        val isIgnoredScalaVersion = test.tags.collect {
          case IgnoreScalaVersion(versions) => versions
        }.flatten

        if (isIgnoredScalaVersion(scalaVersion))
          test.withTags(test.tags + munit.Ignore)
        else test
      })
    )

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

  case class IgnoreScalaVersion(versions: Set[String])
      extends Tag("NoScalaVersion")

  object IgnoreScalaVersion {
    def apply(versions: String*): IgnoreScalaVersion =
      IgnoreScalaVersion(versions.toSet)
}
