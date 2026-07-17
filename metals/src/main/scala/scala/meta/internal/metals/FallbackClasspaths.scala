package scala.meta.internal.metals

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import scala.util.Try

import scala.meta.internal.io.FileIO
import scala.meta.internal.metals.Configs.FallbackClasspathConfig
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.mbt.MbtBuild
import scala.meta.internal.metals.mbt.MbtDependencyModule
import scala.meta.internal.semver.SemVer
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.MavenDependencyModule

trait BaseFallbackClasspaths {
  def javaCompilerClasspath(): Seq[Path]
  def scalaCompilerClasspath(scalaVersion: String): Seq[Path]
}
object EmptyFallbackClasspaths extends BaseFallbackClasspaths {
  override def javaCompilerClasspath(): Seq[Path] = Nil
  override def scalaCompilerClasspath(scalaVersion: String): Seq[Path] = Nil
}

/**
 * Infers the classpath to use for the fallback compiler.
 *
 * In particular, we want to avoid mixing Scala 2.12 and 2.13 dependencies on
 * the same classpath.
 *
 * - For Scala, we only include jars if they're a dependency of a target with a
 *   matching `scalaBinaryVersion` as the user's configured
 *   `fallbackScalaVersion`.
 * - For Java, we only include jars if they're a dependency of another Java
 *   target *and*, if the dependency module looks like a Scala target
 *   (org.scala-lang org, or _2.12/_2.13/_3 suffix in the name), then we only
 *   include it if it matches the user's configured `fallbackScalaVersion`.
 * - For MBT, the *Scala* compiler applies the same rule per module, matching
 *   on inferred Scala binary version; the *Java* compiler takes every MBT
 *   module. Both keep a single jar per logical artifact — javac binds a class
 *   from the first classpath jar containing it, so multiple versions of one
 *   artifact would let the alphabetically-first (oldest) version shadow the
 *   rest.
 */
class FallbackClasspaths(
    workspace: AbsolutePath,
    buildTargets: BuildTargets = BuildTargets.empty,
    fallbackClasspathsConfig: () => FallbackClasspathConfig = () =>
      FallbackClasspathConfig.default,
    scalaVersionSelector: ScalaVersionSelector = ScalaVersionSelector.default,
    mbtBuild: () => MbtBuild,
) extends BaseFallbackClasspaths {
  import FallbackClasspaths._

  private def fallbackCompilerClasspath(
      includeBuildTarget: BuildTargetIdentifier => Boolean,
      includeModule: MavenDependencyModule => Boolean,
      scalaBinaryVersion: String,
      filterMbtByVersion: Boolean,
  ): Seq[Path] = {
    val bspClasspath: Seq[Path] =
      if (fallbackClasspathsConfig().isAll3rdparty) {
        Seq.from(
          buildTargets
            .allDependencyModuleArtifacts(includeBuildTarget, includeModule)
            .map(_.toNIO)
        )
      } else {
        // Assumes we're auto-including scala-library from elsewhere. That logic
        // should ideally be moved into this method so we have one source of truth
        // for what's on the classpath of the fallback compiler.
        Nil
      }
    if (bspClasspath.isEmpty) {
      val paths = mbtClasspath(scalaBinaryVersion, filterMbtByVersion)
      scribe.debug(
        s"fallback-classpath: mbt contributed ${paths.size} jars for Scala $scalaBinaryVersion"
      )
      paths
    } else
      bspClasspath
  }
  def javaCompilerClasspath(): Seq[Path] = {
    val scalaBinaryVersion = fallbackScalaBinaryVersion()
    val result = fallbackCompilerClasspath(
      id =>
        // Technically, we could pick jars from one of Scala 2.12/2.13 targets, but
        // we have no guarantee that the `scalaFallbackVersion` in the user's settings
        // mirrors the Scala version that is allowed as a dependency in Java targets.
        buildTargets.jvmTarget(id).isDefined,
      module =>
        belongsOnClasspath(
          inferScalaBinaryVersion(
            module.getOrganization(),
            module.getName(),
            module.getVersion(),
            scalaBinaryVersion,
          ),
          scalaBinaryVersion,
        ),
      scalaBinaryVersion,
      filterMbtByVersion = false,
    )
    if (result.isEmpty) {
      // Both the BSP and MBT classpaths were empty.
      guessClasspath()
    } else {
      result
    }
  }
  private def fallbackScalaBinaryVersion(): String = {
    val scalaVersion =
      scalaVersionSelector.fallbackScalaVersion()
    ScalaVersions.scalaBinaryVersionFromFullVersion(scalaVersion)
  }

  def scalaCompilerClasspath(scalaVersion: String): Seq[Path] = {
    val scalaBinaryVerion =
      ScalaVersions.scalaBinaryVersionFromFullVersion(scalaVersion)
    fallbackCompilerClasspath(
      id =>
        // IMPORTANT: we must only include dependencies from targets that have a
        // compatible Scala version.  If we include, for example, Java targets then
        // we may implicitly pull in Scala dependencies from an incompatible binary
        // version.
        buildTargets
          .scalaTarget(id)
          .exists(_.scalaBinaryVersion == scalaBinaryVerion),
      _ => true,
      scalaBinaryVerion,
      filterMbtByVersion = true,
    )
  }

  /**
   * Dependency-module jars from the MBT build, version-filtered when
   * `filterByVersion` is set and always deduplicated to one jar per artifact.
   */
  private def mbtClasspath(
      scalaBinaryVersion: String,
      filterByVersion: Boolean,
  ): Seq[Path] = {
    if (fallbackClasspathsConfig().isMbt) {
      val modules = mbtBuild().getDependencyModules.asScala.toSeq
      val eligible =
        if (filterByVersion) {
          modules.filter(module =>
            belongsOnClasspath(
              inferScalaBinaryVersion(
                module.organization,
                module.name,
                module.version,
                scalaBinaryVersion,
              ),
              scalaBinaryVersion,
            )
          )
        } else modules
      dedupByArtifact(eligible, scalaBinaryVersion).flatMap(_.jarPath)
    } else Nil
  }

  private def guessClasspath(): Seq[Path] = {
    if (!fallbackClasspathsConfig().isGuessed) {
      return Nil
    }

    val bazelbsp = workspace.resolve(".bazelbsp/artifacts")
    val bloop = workspace.resolve(".bloop")

    if (bazelbsp.isDirectory) {
      FileIO
        .listAllFilesRecursively(bazelbsp)
        .iterator
        .filter(_.isFile)
        .filter(_.extension == "jar")
        .filter(file =>
          file.toString.contains("third_party") ||
            file.toString.contains("2.12") ||
            file.toString.contains("2.13") ||
            file.toString.contains("shaded")
        )
        .filterNot(_.filename.endsWith("-sources.jar"))
        .map(_.toNIO)
        .toSeq
    } else if (bloop.isDirectory) {
      (for {
        file <- bloop.list.toSeq.iterator
        if file.isFile && file.extension == "json"
        text <- file.readTextOpt.iterator
        json <- Try(ujson.read(text)).toOption.iterator
        project <- json.objOpt.flatMap(_.get("project")).iterator
        classpathEntry <- project.objOpt.flatMap(_.get("classpath")).iterator
        entries <- classpathEntry.arrOpt.iterator
        entry <- entries.iterator.flatMap(_.strOpt.iterator)
        if entry.endsWith(".jar")
      } yield Paths.get(entry)).distinct
        .filter(path => Files.exists(path))
        .toSeq
    } else {
      Nil
    }
  }
}

object FallbackClasspaths {

  /**
   * The Scala binary version a dependency module belongs to. Scala-versioned
   * artifacts are recognised by the `org.scala-lang` organization or a
   * `_2.11`/`_2.12`/`_2.13`/`_3` name suffix; everything else is
   * version-agnostic and reported as `fallbackBinaryVersion`.
   */
  private val scalaBinaryVersionSuffixes = Seq("_3", "_2.13", "_2.12", "_2.11")

  private def inferScalaBinaryVersion(
      organization: String,
      name: String,
      version: String,
      fallbackBinaryVersion: String,
  ): String =
    if (organization == "org.scala-lang")
      ScalaVersions.scalaBinaryVersionFromFullVersion(version)
    else
      scalaBinaryVersionSuffixes
        .find(name.endsWith)
        .map(_.tail)
        .getOrElse(fallbackBinaryVersion)

  /**
   * Whether a dependency module of binary version `moduleBinaryVersion` is
   * eligible for the classpath of a compiler of binary version
   * `compilerBinaryVersion`, with an exception for Scala 3 / 2.13.
   */
  private def belongsOnClasspath(
      moduleBinaryVersion: String,
      compilerBinaryVersion: String,
  ): Boolean =
    moduleBinaryVersion == compilerBinaryVersion ||
      (compilerBinaryVersion == "3" && moduleBinaryVersion == "2.13")

  private def baseArtifactName(name: String): String =
    scalaBinaryVersionSuffixes
      .find(name.endsWith)
      .map(suffix => name.dropRight(suffix.length))
      .getOrElse(name)

  private def versionAtLeast(a: String, b: String): Boolean =
    (
      Try(SemVer.Version.fromString(a)).toOption,
      Try(SemVer.Version.fromString(b)).toOption,
    ) match {
      case (Some(va), Some(vb)) => va >= vb
      case (Some(_), None) => true
      case (None, Some(_)) => false
      case (None, None) => a >= b
    }

  private def preferredModule(
      a: MbtDependencyModule,
      b: MbtDependencyModule,
      compilerBinaryVersion: String,
  ): MbtDependencyModule = {
    def isExactBinary(module: MbtDependencyModule): Boolean =
      inferScalaBinaryVersion(
        module.organization,
        module.name,
        module.version,
        compilerBinaryVersion,
      ) == compilerBinaryVersion
    (isExactBinary(a), isExactBinary(b)) match {
      case (true, false) => a
      case (false, true) => b
      case _ => if (versionAtLeast(a.version, b.version)) a else b
    }
  }

  /**
   * Keep one jar per logical artifact (organization + binary-suffix-stripped
   * name), preferring the copy whose binary version matches the compiler,
   * then the highest semantic version.
   */
  private def dedupByArtifact(
      modules: Seq[MbtDependencyModule],
      compilerBinaryVersion: String,
  ): Seq[MbtDependencyModule] = {
    def keyOf(module: MbtDependencyModule) =
      (module.organization, baseArtifactName(module.name))
    val chosen =
      modules
        .groupBy(keyOf)
        .view
        .mapValues(_.reduceLeft(preferredModule(_, _, compilerBinaryVersion)))
        .toMap
    modules.map(keyOf).distinct.map(chosen)
  }
}
