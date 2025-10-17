package tests

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

import scala.meta.Dialect
import scala.meta.dialects
import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.PackageIndex
import scala.meta.internal.metals.RecursivelyDelete
import scala.meta.internal.mtags.GlobalSymbolIndex
import scala.meta.internal.pc.PresentationCompilerConfigImpl
import scala.meta.internal.pc.ScalaPresentationCompiler
import scala.meta.internal.semver.SemVer
import scala.meta.io.AbsolutePath
import scala.meta.pc.CompletionItemPriority
import scala.meta.pc.PresentationCompiler
import scala.meta.pc.PresentationCompilerConfig

import coursierapi.Dependency
import coursierapi.Fetch
import coursierapi.MavenRepository
import coursierapi.Repository
import munit.Tag
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.jsonrpc.messages.{Either => JEither}

abstract class BasePCSuite extends BaseSuite with PCSuite {

  val scalaNightlyRepo: MavenRepository =
    MavenRepository.of(
      "https://scala-ci.typesafe.com/artifactory/scala-integration/"
    )

  override val allRepos: Seq[Repository] =
    Repository.defaults().asScala.toSeq :+ scalaNightlyRepo

  val executorService: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor()
  val scalaVersion: String = BuildInfoVersions.scalaVersion

  val isNightly: Boolean =
    scalaVersion.contains("-bin-") || scalaVersion.contains("NIGHTLY")

  val completionItemPriority: CompletionItemPriority = (_: String) => 0

  val tmp: AbsolutePath = AbsolutePath(Files.createTempDirectory("metals"))
  val dialect: Dialect =
    if (scalaVersion.startsWith("3.")) dialects.Scala3 else dialects.Scala213

  protected lazy val presentationCompiler: PresentationCompiler = {
    val scalaLibrary =
      if (isScala3Version(scalaVersion))
        PackageIndex.scalaLibrary ++ PackageIndex.scala3Library
      else
        PackageIndex.scalaLibrary

    val fetch = createFetch()
    extraDependencies(scalaVersion).foreach(fetch.addDependencies(_))

    val myclasspath: Seq[Path] = extraLibraries(fetch) ++ scalaLibrary

    if (requiresJdkSources) indexJdkSources
    if (requiresScalaLibrarySources)
      indexScalaLibrary(index, scalaVersion)

    val scalacOpts = scalacOptions(myclasspath)

    new ScalaPresentationCompiler()
      .withSearch(search(myclasspath))
      .withConfiguration(config)
      .withExecutorService(executorService)
      .withScheduledExecutorService(executorService)
      .withCompletionItemPriority(completionItemPriority)
      .newInstance("", myclasspath.asJava, scalacOpts.asJava, Nil.asJava)
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
      scalaVersion: String
  ): Unit = {
    val libDependency =
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
    val deps = if (isScala3Version(scalaVersion)) {
      Seq(
        libDependency,
        Dependency.of(
          "org.scala-lang",
          "scala3-library_3",
          scalaVersion
        )
      )
    } else {
      Seq(libDependency)
    }
    val sources = Fetch
      .create()
      .withClassifiers(Set("sources").asJava)
      .withDependencies(deps: _*)
      .withRepositories(allRepos: _*)
      .fetch()
      .asScala
    sources.foreach { jar =>
      val dialect = if (isScala3Version(scalaVersion)) {
        dialects.Scala3
      } else {
        dialects.Scala213
      }
      index.addSourceJar(AbsolutePath(jar), dialect)
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
        }
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
        }
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

        }
      )
    )

  override def params(
      code: String,
      filename: String = "test.scala"
  ): (String, Int) = super.params(code, filename)

  override def hoverParams(
      code: String,
      filename: String = "test.scala"
  ): (String, Int, Int) = super.hoverParams(code, filename)

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
      extends Tag("NoScalaVersion") {
    def and(other: IgnoreScalaVersion): IgnoreScalaVersion =
      IgnoreScalaVersion { v =>
        ignored(v) || other.ignored(v)
      }
  }

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

    def forLaterOrEqualTo(version: String): IgnoreScalaVersion = {
      val disableFrom = SemVer.Version.fromString(version)
      IgnoreScalaVersion { v =>
        val semver = SemVer.Version.fromString(v)
        !(disableFrom > semver)
      }
    }

  }

  object IgnoreScala2 extends IgnoreScalaVersion(_.startsWith("2."))
  object IgnoreScala2Nightlies
      extends IgnoreScalaVersion(version =>
        version.startsWith("2.") && version.contains("-bin-")
      )

  object IgnoreScala213 extends IgnoreScalaVersion(_.startsWith("2.13"))

  object IgnoreScala212 extends IgnoreScalaVersion(_.startsWith("2.12"))

  object IgnoreScala211 extends IgnoreScalaVersion(_.startsWith("2.11"))

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
