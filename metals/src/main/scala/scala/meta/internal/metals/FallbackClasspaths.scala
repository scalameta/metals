package scala.meta.internal.metals

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import scala.util.Try

import scala.meta.internal.io.FileIO
import scala.meta.internal.metals.Configs.FallbackClasspathConfig
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.MavenDependencyModule

trait BaseFallbackClasspaths {
  def javaCompilerClasspath(): Seq[Path]
  def scalaCompilerClasspath(): Seq[Path]
}
object EmptyFallbackClasspaths extends BaseFallbackClasspaths {
  override def javaCompilerClasspath(): Seq[Path] = Nil
  override def scalaCompilerClasspath(): Seq[Path] = Nil
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
 */
class FallbackClasspaths(
    workspace: AbsolutePath,
    buildTargets: BuildTargets = BuildTargets.empty,
    fallbackClasspathsConfig: () => FallbackClasspathConfig = () =>
      FallbackClasspathConfig.default,
    scalaVersionSelector: ScalaVersionSelector = ScalaVersionSelector.default,
) extends BaseFallbackClasspaths {
  private def fallbackCompilerClasspath(
      includeBuildTarget: BuildTargetIdentifier => Boolean,
      includeModule: MavenDependencyModule => Boolean,
  ): Seq[Path] = {
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
  }
  def javaCompilerClasspath(): Seq[Path] = {
    val scalaBinaryVersion = fallbackScalaBinaryVersion()
    def inferScalaBinaryVersion(
        module: MavenDependencyModule
    ): String = {
      if (module.getOrganization() == "org.scala-lang") {
        ScalaVersions.scalaBinaryVersionFromFullVersion(module.getVersion())
      } else if (module.getName().endsWith("_3")) {
        "3"
      } else if (module.getName().endsWith("_2.13")) {
        "2.13"
      } else if (module.getName().endsWith("_2.12")) {
        "2.12"
      } else {
        // Technically: None.
        scalaBinaryVersion
      }
    }
    val result = fallbackCompilerClasspath(
      id =>
        // Technically, we could pick jars from one of Scala 2.12/2.13 targets, but
        // we have no guarantee that the `scalaFallbackVersion` in the user's settings
        // mirrors the Scala version that is allowed as a dependeny in Java targets.
        buildTargets.javaTarget(id).isDefined,
      module => inferScalaBinaryVersion(module) == scalaBinaryVersion,
    )
    if (result.isEmpty) {
      guessClasspath()
    } else {
      result
    }
  }
  private def fallbackScalaBinaryVersion(): String = {
    val scalaVersion =
      scalaVersionSelector.fallbackScalaVersion(isAmmonite = false)
    ScalaVersions.scalaBinaryVersionFromFullVersion(scalaVersion)
  }
  def scalaCompilerClasspath(): Seq[Path] = {
    val scalaBinaryVerion = fallbackScalaBinaryVersion()
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
    )
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
