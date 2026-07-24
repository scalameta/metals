package scala.meta.internal.metals.mbt.importer

import java.nio.file.Path

import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.mbt.MbtDependencyModule

object BazelEffectiveScalaVersionResolver {

  /**
   * The workspace's default Scala version as rules_scala resolved it. See
   * [[ScalaToolchainModules.scalaConfigVersion]].
   */
  private def scalaVersionFromConfigRepo(
      outputBase: Option[Path]
  ): Option[String] =
    outputBase.flatMap { base =>
      ScalaToolchainModules.scalaConfigVersion(base.resolve("external"))
    }

  private def scalaVersionFromModules(
      modules: Seq[MbtDependencyModule]
  ): Option[String] = {
    val versions = modules.collect {
      case module
          if module.id.startsWith("org.scala-lang:scala-library:") ||
            module.id.startsWith("org.scala-lang:scala3-library_3:") =>
        module.id.split(":").last
    }
    BazelScalaVersions.maxVersion(versions)
  }

  def resolve(
      outputBase: Option[Path],
      dependencyModules: Seq[MbtDependencyModule],
      scalaVersions: Map[String, List[String]],
      userConfiguration: UserConfiguration,
  ): Option[String] = {
    scalaVersionFromConfigRepo(outputBase)
      .orElse(BazelScalaVersions.maxVersion(scalaVersions.values.flatten))
      .orElse(scalaVersionFromModules(dependencyModules))
      .orElse(userConfiguration.fallbackScalaVersion)
  }

}
