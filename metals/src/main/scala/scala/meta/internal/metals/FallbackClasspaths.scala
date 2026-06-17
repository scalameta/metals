package scala.meta.internal.metals

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import scala.util.Try

import scala.meta.internal.io.FileIO
import scala.meta.internal.metals.Configs.FallbackClasspathConfig
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.mbt.MbtBuild
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
 * - For MBT, where there are no per-target build targets to filter on, we apply
 *   the same rule per module: a module joins the fallback classpath only if its
 *   inferred Scala binary version matches the compiler's (version-agnostic Java
 *   modules are always included). Without this, a workspace that cross-compiles
 *   across Scala versions (e.g. rules_scala: 2.11/2.12/2.13/3) would pile every
 *   version's `scala-library` — and a Scala 3 compiler — onto one 2.x
 *   compiler's classpath, corrupting its symbol table.
 *
 * One exception to the equality rule: a Scala 3 compiler runs on the Scala 2.13
 * standard library, so `2.13` modules belong on a Scala `3` classpath (see
 * [[belongsOnClasspath]]). This is not the corruption case above — `scala3-
 * library_3` is additive over the single 2.13 `scala-library`, not a competing
 * second copy.
 */
class FallbackClasspaths(
    workspace: AbsolutePath,
    buildTargets: BuildTargets = BuildTargets.empty,
    fallbackClasspathsConfig: () => FallbackClasspathConfig = () =>
      FallbackClasspathConfig.default,
    scalaVersionSelector: ScalaVersionSelector = ScalaVersionSelector.default,
    mbtBuild: () => MbtBuild,
) extends BaseFallbackClasspaths {
  private def fallbackCompilerClasspath(
      includeBuildTarget: BuildTargetIdentifier => Boolean,
      includeModule: MavenDependencyModule => Boolean,
      scalaBinaryVersion: String,
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
      val paths = mbtClasspath(scalaBinaryVersion)
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
    )
    if (result.isEmpty) {
      guessClasspath() ++ mbtClasspath(scalaBinaryVersion)
    } else {
      result
    }
  }
  private def fallbackScalaBinaryVersion(): String = {
    val scalaVersion =
      scalaVersionSelector.fallbackScalaVersion()
    ScalaVersions.scalaBinaryVersionFromFullVersion(scalaVersion)
  }

  /**
   * The Scala binary version a dependency module belongs to. Scala-versioned
   * artifacts are recognised by the `org.scala-lang` organization or a
   * `_2.11`/`_2.12`/`_2.13`/`_3` name suffix; everything else (a plain Java
   * library) is version-agnostic and reported as `fallbackBinaryVersion` so it
   * always passes an equality check against the compiler's version.
   */
  private def inferScalaBinaryVersion(
      organization: String,
      name: String,
      version: String,
      fallbackBinaryVersion: String,
  ): String =
    if (organization == "org.scala-lang")
      ScalaVersions.scalaBinaryVersionFromFullVersion(version)
    else if (name.endsWith("_3")) "3"
    else if (name.endsWith("_2.13")) "2.13"
    else if (name.endsWith("_2.12")) "2.12"
    else if (name.endsWith("_2.11")) "2.11"
    else fallbackBinaryVersion

  /**
   * Whether a dependency module of binary version `moduleBinaryVersion` belongs
   * on the classpath of a compiler of binary version `compilerBinaryVersion`.
   *
   * Exact matches always belong. Additionally, a Scala 3 compiler runs on the
   * Scala 2.13 standard library and consumes 2.13 artifacts via the Scala 3 /
   * 2.13 forward compatibility, so `2.13` modules belong on a `3` classpath —
   * without the 2.13 `scala-library` the Scala 3 presentation compiler has no
   * standard library and types nothing.
   */
  private def belongsOnClasspath(
      moduleBinaryVersion: String,
      compilerBinaryVersion: String,
  ): Boolean =
    moduleBinaryVersion == compilerBinaryVersion ||
      (compilerBinaryVersion == "3" && moduleBinaryVersion == "2.13")
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
    )
  }

  /**
   * Dependency-module jars from the MBT build whose Scala binary version
   * matches `scalaBinaryVersion`, plus version-agnostic Java jars. A single
   * fallback presentation compiler is created per Scala version
   * ([[Compilers.fallbackCompiler]]); without this filter a cross-compiled
   * workspace would place every version's `scala-library` (and a Scala 3
   * compiler) on one 2.x compiler's classpath, corrupting its symbol table.
   */
  private def mbtClasspath(scalaBinaryVersion: String): Seq[Path] = {
    if (!fallbackClasspathsConfig().isMbt) {
      return Nil
    }
    mbtBuild().getDependencyModules.asScala.toSeq
      .filter(module =>
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
      .flatMap(_.jarPath)
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
