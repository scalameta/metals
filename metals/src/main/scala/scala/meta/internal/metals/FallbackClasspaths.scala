package scala.meta.internal.metals

import java.nio.file.Path

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.MavenDependencyModule

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
    buildTargets: BuildTargets,
    userConfig: () => UserConfiguration,
    scalaVersionSelector: ScalaVersionSelector,
) {
  private def fallbackCompilerClasspath(
      includeBuildTarget: BuildTargetIdentifier => Boolean,
      includeModule: MavenDependencyModule => Boolean,
  ): Seq[Path] = {
    if (userConfig().fallbackClasspath.isAll3rdparty) {
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
    result
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
}
