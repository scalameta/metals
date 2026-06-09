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
    classDirectories: Seq[String],
    testClassDirectory: Seq[String],
    sourceDirectories: Seq[String],
    testSourceDirectories: Seq[String],
    externalDependencies: Seq[ExternalDependency],
    projectDependencies: Seq[ProjectDependency],
    testFixturesSources: Seq[String] = Nil,
    testFixturesClassDirectories: Seq[String] = Nil,
    testFixturesProjectDeps: Seq[String] = Nil,
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

    val modulesWithTestFixtures = modules.collect {
      case m
          if m.testFixturesSources.nonEmpty || m.testFixturesClassDirectories.nonEmpty =>
        m.name
    }.toSet

    val namespaces = modules.flatMap { m =>
      val mainDepIds = m.externalDependencies
        .filter(_.scope != "TEST")
        .map(_.gav)
        .distinct
      val testDepIds = m.externalDependencies.map(_.gav).distinct
      val mainDependsOn = m.projectDependencies
        .filter(_.scope != "TEST")
        .map(_.targetModule)
        .distinct
      val testDependsOn = m.projectDependencies.map(_.targetModule).distinct

      val mainNamespace = m.name -> MbtNamespaceJson(
        sources = m.sourceDirectories,
        scalacOptions = Seq.empty,
        javacOptions = Seq.empty,
        dependencyModules = mainDepIds,
        scalaVersion = null,
        javaHome = javaHome,
        dependsOn = if (mainDependsOn.nonEmpty) mainDependsOn else null,
        classDirectories = m.classDirectories,
        projectPath = m.projectPath,
        configurations = null,
      )

      val testNamespace =
        if (m.testSourceDirectories.isEmpty && m.testClassDirectory.isEmpty)
          None
        else {
          val testDeps = m.name :: testDependsOn.toList
          val fixtureDeps = testDeps
            .filter(modulesWithTestFixtures)
            .map(dep => s"$dep:testFixtures")
          Some(
            s"${m.name}:test" -> MbtNamespaceJson(
              sources = m.testSourceDirectories,
              scalacOptions = Seq.empty,
              javacOptions = Seq.empty,
              dependencyModules = testDepIds,
              scalaVersion = null,
              javaHome = javaHome,
              dependsOn = (testDeps ++ fixtureDeps).distinct,
              classDirectories = m.testClassDirectory,
              projectPath = m.projectPath,
              configurations = null,
            )
          )
        }

      val testFixturesNamespace =
        Option.when(
          m.testFixturesSources.nonEmpty || m.testFixturesClassDirectories.nonEmpty
        )(
          s"${m.name}:testFixtures" -> MbtNamespaceJson(
            sources = m.testFixturesSources,
            scalacOptions = Seq.empty,
            javacOptions = Seq.empty,
            dependencyModules = testDepIds,
            scalaVersion = null,
            javaHome = javaHome,
            dependsOn =
              (m.name +: (mainDependsOn ++ m.testFixturesProjectDeps)).distinct,
            classDirectories = m.testFixturesClassDirectories,
            projectPath = m.projectPath,
            configurations = null,
          )
        )

      Seq(Some(mainNamespace), testNamespace, testFixturesNamespace).flatten
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
    classDirectories: Seq[String],
    projectPath: String,
    configurations: Seq[String],
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
