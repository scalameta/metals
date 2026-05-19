package gradleinfo

import upickle.default._

/** A single external (third-party) library a module depends on. */
final case class ExternalDependency(
    group: Option[String],
    name: Option[String],
    version: Option[String],
    scope: String,
    file: Option[String],
    source: Option[String],
) {
  def gav: String = {
    val result = List(group, name, version).flatten.mkString(":")
    if (result.nonEmpty) result
    else {
      file
        .map(_.split("/|\\\\").lastOption)
        .flatten
        .map(_.split("\\.").head)
        .getOrElse {
          throw new RuntimeException(
            "No group, name, or version and no file available for this dependency"
          )
        }
    }
  }
}

/** A dependency on another module within the same Gradle build. */
final case class ProjectDependency(
    targetModule: String,
    scope: String,
)

/** Information about a single Gradle (sub)module. */
final case class ModuleReport(
    name: String,
    projectPath: String,
    projectDir: String,
    description: Option[String],
    javaSourceLevel: Option[String],
    javaTargetLevel: Option[String],
    sourceDirectories: Seq[String],
    testSourceDirectories: Seq[String],
    externalDependencies: Seq[ExternalDependency],
    projectDependencies: Seq[ProjectDependency],
)

/** Top-level information about the Gradle build. */
final case class ProjectReport(
    rootName: String,
    rootDir: String,
    gradleVersion: String,
    javaHome: String,
    modules: Seq[ModuleReport],
) {

  /** All distinct GAV coordinates encountered across every module. */
  def distinctExternalDependencies: Seq[ExternalDependency] =
    modules
      .flatMap(_.externalDependencies)
      .distinctBy(d => (d.group, d.name, d.version))
      .sortBy(d => (d.group, d.name, d.version))

  /** Convert this report to MbtBuild-compatible JSON. */
  def toMbtJson: String = {
    val allDeps = distinctExternalDependencies
    val depModules = allDeps.map { dep =>
      MbtDependencyModuleJson(
        id = dep.gav,
        jar = dep.file.orNull,
        sources = dep.source.orNull,
      )
    }

    val namespaces = modules.map { m =>
      val allSources = m.sourceDirectories ++ m.testSourceDirectories
      val depIds = m.externalDependencies.map(_.gav).distinct
      val dependsOn = m.projectDependencies.map(_.targetModule).distinct

      m.name -> MbtNamespaceJson(
        sources = allSources,
        scalacOptions = Seq.empty,
        javacOptions = Seq.empty,
        dependencyModules = depIds,
        scalaVersion = null,
        javaHome = javaHome,
        dependsOn = if (dependsOn.nonEmpty) dependsOn else null,
      )
    }.toMap

    val build = MbtBuildJson(
      dependencyModules = depModules,
      namespaces = namespaces,
    )
    write(build, indent = 2)
  }
}

/** JSON model for MbtDependencyModule compatible with MbtBuild. */
final case class MbtDependencyModuleJson(
    id: String,
    jar: String,
    sources: String,
)

object MbtDependencyModuleJson {
  implicit val rw: ReadWriter[MbtDependencyModuleJson] = macroRW
}

/** JSON model for MbtNamespace compatible with MbtBuild. */
final case class MbtNamespaceJson(
    sources: Seq[String],
    scalacOptions: Seq[String],
    javacOptions: Seq[String],
    dependencyModules: Seq[String],
    scalaVersion: String,
    javaHome: String,
    dependsOn: Seq[String],
)

object MbtNamespaceJson {
  implicit val rw: ReadWriter[MbtNamespaceJson] = macroRW
}

/** JSON model for MbtBuild. */
final case class MbtBuildJson(
    dependencyModules: Seq[MbtDependencyModuleJson],
    namespaces: Map[String, MbtNamespaceJson],
)

object MbtBuildJson {
  implicit val rw: ReadWriter[MbtBuildJson] = macroRW
}
