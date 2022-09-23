package tests

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

import scala.util.control.NonFatal

import scala.meta.dialects
import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.ClasspathSearch
import scala.meta.internal.metals.Docstrings
import scala.meta.internal.metals.ExcludedPackagesHandler
import scala.meta.internal.metals.JdkSources
import scala.meta.internal.metals.PackageIndex
import scala.meta.internal.metals.RecursivelyDelete
import scala.meta.internal.mtags.GlobalSymbolIndex
import scala.meta.internal.pc.PresentationCompilerConfigImpl
import scala.meta.internal.pc.ScalaPresentationCompiler
import scala.meta.internal.semver.SemVer
import scala.meta.io.AbsolutePath
import scala.meta.pc.PresentationCompiler
import scala.meta.pc.PresentationCompilerConfig

import coursierapi.Dependency
import coursierapi.Fetch
import coursierapi.MavenRepository
import coursierapi.Repository
import munit.Tag
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.jsonrpc.messages.{Either => JEither}

abstract class BasePCSuite extends BaseSuite {

  val scalaNightlyRepo: MavenRepository =
    MavenRepository.of(
      "https://scala-ci.typesafe.com/artifactory/scala-integration/"
    )
  val allRepos: Seq[Repository] =
    Repository.defaults().asScala.toSeq :+ scalaNightlyRepo

  val executorService: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor()
  val scalaVersion = BuildInfoVersions.scalaVersion
  protected val index = new DelegatingGlobalSymbolIndex()
  protected val workspace = new TestingWorkspaceSearch
  val tmp: AbsolutePath = AbsolutePath(Files.createTempDirectory("metals"))

  protected lazy val presentationCompiler: PresentationCompiler = {
    val scalaLibrary =
      if (isScala3Version(scalaVersion))
        PackageIndex.scalaLibrary ++ PackageIndex.scala3Library
      else
        PackageIndex.scalaLibrary

    val fetch = Fetch
      .create()
      .withRepositories(allRepos: _*)

    extraDependencies(scalaVersion).foreach(fetch.addDependencies(_))
    val extraLibraries: Seq[Path] = fetch
      .fetch()
      .asScala
      .map(_.toPath())
      .toSeq

    val myclasspath: Seq[Path] = extraLibraries ++ scalaLibrary

    if (requiresJdkSources)
      JdkSources().foreach(jdk => index.addSourceJar(jdk, dialects.Scala213))
    if (requiresScalaLibrarySources)
      indexScalaLibrary(index, scalaVersion)
    val search = new TestingSymbolSearch(
      ClasspathSearch
        .fromClasspath(myclasspath, ExcludedPackagesHandler.default),
      new Docstrings(index),
      workspace,
      index,
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

  protected def extraDependencies(scalaVersion: String): Seq[Dependency] =
    Seq.empty

  protected def scalacOptions(classpath: Seq[Path]): Seq[String] = Seq.empty

  protected def ignoreScalaVersion: Option[IgnoreScalaVersion] = None

  protected def requiresJdkSources: Boolean = false

  protected def requiresScalaLibrarySources: Boolean = false

  protected def isScala3Version(scalaVersion: String): Boolean = {
    scalaVersion.startsWith("3.")
  }

  protected def createBinaryVersion(scalaVersion: String): String = {
    if (isScala3Version(scalaVersion))
      "3"
    else
      scalaVersion.split('.').take(2).mkString(".")
  }

  private def indexScalaLibrary(
      index: GlobalSymbolIndex,
      scalaVersion: String,
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
          },
        )
      )
      .withRepositories(allRepos: _*)
      .fetch()
      .asScala
    sources.foreach { jar =>
      index.addSourceJar(AbsolutePath(jar), dialects.Scala213)
    }
  }

  override def afterAll(): Unit = {
    presentationCompiler.shutdown()
    RecursivelyDelete(tmp)
    executorService.shutdown()
  }

  override def munitIgnore: Boolean =
    ignoreScalaVersion.exists(_.ignored(scalaVersion))

  override def munitTestTransforms: List[TestTransform] =
    super.munitTestTransforms ++ List(
      new TestTransform(
        "Append Scala version",
        { test =>
          test.withName(test.name + "_" + scalaVersion)
        },
      ),
      new TestTransform(
        "Ignore Scala version",
        { test =>
          val isIgnoredScalaVersion = test.tags.exists {
            case IgnoreScalaVersion(isIgnored) => isIgnored(scalaVersion)
            case _ => false
          }

          if (isIgnoredScalaVersion)
            test.withTags(test.tags + munit.Ignore)
          else test
        },
      ),
      new TestTransform(
        "Run for Scala version",
        { test =>
          test.tags
            .collectFirst { case RunForScalaVersion(versions) =>
              if (versions(scalaVersion))
                test
              else test.withTags(test.tags + munit.Ignore)
            }
            .getOrElse(test)

        },
      ),
    )

  def params(code: String, filename: String = "test.scala"): (String, Int) = {
    val code2 = code.replace("@@", "")
    val offset = code.indexOf("@@")
    if (offset < 0) {
      fail("missing @@")
    }
    inspectDialect(filename, code2)
    (code2, offset)
  }

  def hoverParams(
      code: String,
      filename: String = "test.scala",
  ): (String, Int, Int) = {
    val code2 = code.replace("@@", "").replace("%<%", "").replace("%>%", "")
    val positionOffset =
      code.replace("%<%", "").replace("%>%", "").indexOf("@@")
    val startOffset = code.replace("@@", "").indexOf("%<%")
    val endOffset = code.replace("@@", "").replace("%<%", "").indexOf("%>%")
    (positionOffset, startOffset, endOffset) match {
      case (po, so, eo) if po < 0 && so < 0 && eo < 0 =>
        fail("missing @@ and (%<% and %>%)")
      case (_, so, eo) if so >= 0 && eo >= 0 =>
        (code2, so, eo)
      case (po, _, _) =>
        inspectDialect(filename, code2)
        (code2, po, po)
    }
  }

  private def inspectDialect(filename: String, code2: String) = {
    val file = tmp.resolve(filename)
    Files.write(file.toNIO, code2.getBytes(StandardCharsets.UTF_8))
    val dialect =
      if (scalaVersion.startsWith("3.")) dialects.Scala3 else dialects.Scala213
    try index.addSourceFile(file, Some(tmp), dialect)
    catch {
      case NonFatal(e) =>
        println(s"warn: ${e.getMessage()}")
    }
    workspace.inputs(filename) = (code2, dialect)
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

  case class IgnoreScalaVersion(ignored: String => Boolean)
      extends Tag("NoScalaVersion")

  object IgnoreScalaVersion {
    def apply(version: String): IgnoreScalaVersion = {
      IgnoreScalaVersion(_ == version)
    }
    def apply(versions: String*): IgnoreScalaVersion = {
      IgnoreScalaVersion(versions.toSet.apply(_))
    }

    def for3LessThan(version: String): IgnoreScalaVersion = {
      val enableFrom = SemVer.Version.fromString(version)
      IgnoreScalaVersion { v =>
        val semver = SemVer.Version.fromString(v)
        semver.major == 3 && !(semver >= enableFrom)
      }
    }

    def forRangeUntil(from: String, until: String): IgnoreScalaVersion = {
      val fromV = SemVer.Version.fromString(from)
      val untilV = SemVer.Version.fromString(until)
      IgnoreScalaVersion { v =>
        val semver = SemVer.Version.fromString(v)
        semver < untilV && semver >= fromV
      }
    }

    def forLessThan(version: String): IgnoreScalaVersion = {
      val enableFrom = SemVer.Version.fromString(version)
      IgnoreScalaVersion { v =>
        val semver = SemVer.Version.fromString(v)
        !(semver >= enableFrom)
      }
    }

    def forLaterThan(version: String): IgnoreScalaVersion = {
      val disableFrom = SemVer.Version.fromString(version)
      IgnoreScalaVersion { v =>
        val semver = SemVer.Version.fromString(v)
        !(disableFrom > semver)
      }
    }

  }

  object IgnoreScala2 extends IgnoreScalaVersion(_.startsWith("2."))

  object IgnoreScala212 extends IgnoreScalaVersion(_.startsWith("2.12"))

  object IgnoreScala3 extends IgnoreScalaVersion(_.startsWith("3."))

  case class RunForScalaVersion(versions: Set[String])
      extends Tag("RunScalaVersion")

  object RunForScalaVersion {
    def apply(versions: Seq[String]): RunForScalaVersion =
      RunForScalaVersion(versions.toSet)

    def apply(version: String): RunForScalaVersion = {
      RunForScalaVersion(Set(version))
    }
  }
}
