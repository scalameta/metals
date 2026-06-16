package gradleinfo

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger

import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ModelBuilder
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.model.build.BuildEnvironment
import org.gradle.tooling.model.gradle.GradleBuild
import org.gradle.tooling.model.idea.IdeaContentRoot
import org.gradle.tooling.model.idea.IdeaDependency
import org.gradle.tooling.model.idea.IdeaModule
import org.gradle.tooling.model.idea.IdeaModuleDependency
import org.gradle.tooling.model.idea.IdeaProject
import org.gradle.tooling.model.idea.IdeaSingleEntryLibraryDependency
import upickle.default.ReadWriter
import upickle.default.macroRW
import upickle.default.read

/** Configuration for a single extraction run. */
final case class ExtractorConfig(
    projectDir: Path,
    gradleVersion: Option[String] = None,
    gradleInstallation: Option[File] = None,
    gradleUserHome: Option[File] = None,
    /**
     * Path to a JDK installation that the Gradle daemon should use (passed
     * directly to [[org.gradle.tooling.ConfigurableLauncher#setJavaHome]]).
     * Must point to a full JDK directory (i.e. one that contains `bin/javac`),
     * not a bare JRE; Gradle requires compiler tooling at runtime. When unset
     * the daemon inherits the client's JVM, which can be a problem when the
     * client JVM is newer than the version the daemon's bytecode toolchain
     * understands.
     */
    gradleJvm: Option[File] = None,
    verbose: Boolean = false,
)

/** Pulls structural information out of a Gradle build via the Tooling API. */
object GradleInfoExtractor {
  private val logger = Logger.getLogger(getClass.getName)

  private final case class SourceSetDirectories(
      classDirectories: List[String] = Nil,
      testClassDirectory: List[String] = Nil,
      testFixturesClassDirectories: List[String] = Nil,
      testFixturesSources: List[String] = Nil,
      testFixturesProjectDeps: List[String] = Nil,
  )

  private object SourceSetDirectories {
    implicit val rw: ReadWriter[SourceSetDirectories] = macroRW
  }

  def extract(config: ExtractorConfig): ProjectReport = {
    val absRoot = config.projectDir.toFile.getCanonicalFile()
    require(absRoot.isDirectory, s"Not a directory: $absRoot")

    val connection = buildConnector(absRoot, config).connect()
    try {
      val env = fetchModel(connection, classOf[BuildEnvironment], config)
      val (idea, modules) = fetchIdeaModules(connection, config, config)

      val gradleBuild = fetchModel(connection, classOf[GradleBuild], config)
      val editableModules = gradleBuild.getEditableBuilds.asScala.toSeq
        .flatMap(extractEditableBuildModules(_, absRoot.toPath, config))
      val allModules =
        normalizeCompositeProjectDependencies(modules ++ editableModules)

      ProjectReport(
        rootName = idea.getName,
        rootDir = absRoot.getAbsolutePath,
        gradleVersion = env.getGradle.getGradleVersion,
        javaHome = env.getJava.getJavaHome.toPath().toUri().toString(),
        modules = allModules.sortBy(_.projectPath),
      )
    } finally connection.close()
  }

  private val UnresolvedDependencyPrefix = "unresolved dependency - "

  private def normalizeCompositeProjectDependencies(
      modules: Seq[ModuleReport]
  ): Seq[ModuleReport] = {
    val moduleNames = modules.map(_.name).toSet
    modules.map { module =>
      val (compositeProjectDeps, externalDeps) =
        module.externalDependencies.partitionMap { dependency =>
          compositeProjectDependency(dependency, moduleNames).toLeft(dependency)
        }
      module.copy(
        externalDependencies =
          externalDeps.sortBy(d => (d.group, d.name, d.version, d.scope)),
        projectDependencies =
          (module.projectDependencies ++ compositeProjectDeps).distinct
            .sortBy(d => (d.targetModule, d.scope)),
      )
    }
  }

  private def compositeProjectDependency(
      dependency: ExternalDependency,
      moduleNames: Set[String],
  ): Option[ProjectDependency] =
    Option
      .when(dependency.gav.startsWith(UnresolvedDependencyPrefix)) {
        dependency.gav
          .stripPrefix(UnresolvedDependencyPrefix)
          .split("\\s+")
          .dropRight(1)
          .reverse
      }
      .flatMap(_.find(moduleNames))
      .map(ProjectDependency(_, dependency.scope))

  private def extractEditableBuildModules(
      editableBuild: GradleBuild,
      mainProjectDir: Path,
      config: ExtractorConfig,
  ): Seq[ModuleReport] =
    Option(editableBuild.getRootProject)
      .flatMap(project => Option(project.getProjectDirectory))
      .map(_.toPath.toAbsolutePath.normalize.toFile.getCanonicalFile())
      .filter(_.isDirectory)
      .fold(Seq.empty[ModuleReport]) { editableBuildDir =>
        val editableConfig = config.copy(projectDir = editableBuildDir.toPath)
        val connection = buildConnector(editableBuildDir, config).connect()
        try {
          val mainConfig = config.copy(projectDir = mainProjectDir)
          val includedBuildName =
            Option(editableBuild.getRootProject)
              .flatMap(p => Option(p.getName))
              .getOrElse("")
          val (_, modules) = fetchIdeaModules(
            connection,
            fetchConfig = editableConfig,
            relativeToConfig = mainConfig,
            extraArgs = List("--dependency-verification", "off"),
          )
          modules.map(m =>
            m.copy(projectPath =
              prefixProjectPath(m.projectPath, includedBuildName)
            )
          )
        } catch {
          case NonFatal(e) =>
            logger.log(
              Level.WARNING,
              s"Failed to extract modules from editable build $editableBuildDir",
              e,
            )
            Nil
        } finally connection.close()
      }

  private def buildConnector(
      dir: java.io.File,
      config: ExtractorConfig,
  ): GradleConnector = {
    val connector = GradleConnector.newConnector().forProjectDirectory(dir)
    config.gradleVersion.foreach(connector.useGradleVersion)
    config.gradleInstallation.foreach(connector.useInstallation)
    config.gradleUserHome.foreach(connector.useGradleUserHomeDir)
    connector
  }

  private def fetchIdeaModules(
      connection: ProjectConnection,
      fetchConfig: ExtractorConfig,
      relativeToConfig: ExtractorConfig,
      extraArgs: List[String] = Nil,
  ): (IdeaProject, Seq[ModuleReport]) = {
    val sourceSetsOutputFile =
      Files.createTempFile("metals-sourcesets", ".json")
    val initScriptFile = writeSourceSetsInitScript(sourceSetsOutputFile)
    try {
      val idea = fetchModel(
        connection,
        classOf[IdeaProject],
        fetchConfig,
        extraArgs = List("--init-script", initScriptFile.toString) ::: extraArgs,
      )
      val sourceSetsMap = readSourceSetsMap(sourceSetsOutputFile)
      val modules = idea.getModules.asScala.toSeq
        .map(extractModule(_, relativeToConfig, sourceSetsMap))
      (idea, modules)
    } finally {
      Files.deleteIfExists(initScriptFile)
      Files.deleteIfExists(sourceSetsOutputFile)
    }
  }

  private def writeSourceSetsInitScript(outputFile: Path): Path = {
    val escapedPath =
      outputFile.toString.replace("\\", "\\\\").replace("'", "\\'")
    val script =
      s"""|gradle.projectsEvaluated {
          |  def result = [:]
          |  gradle.rootProject.allprojects { project ->
          |    def sourceSets = project.extensions.findByName('sourceSets')
          |    if (sourceSets != null) {
          |      def main = sourceSets.findByName('main')
          |      def test = sourceSets.findByName('test')
          |      def testFixtures = sourceSets.findByName('testFixtures')
          |      def outputs = [:]
          |      if (main != null) {
          |        outputs['classDirectories'] = main.output.classesDirs.files.collect { it.absolutePath }
          |      }
          |      if (test != null) {
          |        outputs['testClassDirectory'] = test.output.classesDirs.files.collect { it.absolutePath }
          |      }
          |      if (testFixtures != null) {
          |        outputs['testFixturesClassDirectories'] = testFixtures.output.classesDirs.files.collect { it.absolutePath }
          |        outputs['testFixturesSources'] = testFixtures.allSource.srcDirs.findAll { it.exists() }.collect { it.absolutePath }
          |        def tfConfig = project.configurations.findByName('testFixturesImplementation')
          |        if (tfConfig != null) {
          |          outputs['testFixturesProjectDeps'] = tfConfig.dependencies
          |            .findAll { it instanceof org.gradle.api.artifacts.ProjectDependency }
          |            .collect { it.name }
          |        }
          |      }
          |      if (!outputs.isEmpty()) {
          |        result[project.path] = outputs
          |      }
          |    }
          |  }
          |  new File('$escapedPath').text = groovy.json.JsonOutput.toJson(result)
          |}
          |""".stripMargin
    val initFile = Files.createTempFile("metals-sourcesets-init", ".gradle")
    Files.writeString(initFile, script)
    initFile
  }

  private def readSourceSetsMap(
      outputFile: Path
  ): Map[String, SourceSetDirectories] =
    try {
      if (Files.exists(outputFile) && Files.size(outputFile) > 0)
        read[Map[String, SourceSetDirectories]](Files.readString(outputFile))
      else Map.empty
    } catch {
      case NonFatal(_) => Map.empty
    }

  /**
   * Build a model with all per-operation Tooling API settings applied
   * (currently just the optional Java home for the daemon).
   */
  private def fetchModel[T](
      connection: ProjectConnection,
      modelType: Class[T],
      config: ExtractorConfig,
      extraArgs: List[String] = Nil,
  ): T = {
    val builder: ModelBuilder[T] = connection.model(modelType)
    config.gradleJvm.foreach(builder.setJavaHome)
    if (extraArgs.nonEmpty) builder.withArguments(extraArgs: _*)
    builder.get()
  }

  private def extractModule(
      m: IdeaModule,
      config: ExtractorConfig,
      sourceSetsMap: Map[String, SourceSetDirectories],
  ): ModuleReport = {
    val gradleProject = m.getGradleProject
    val projectPath = Option(gradleProject).map(_.getPath).getOrElse(":")
    val projectDir =
      Option(gradleProject)
        .flatMap(p => Option(p.getProjectDirectory))
        .map(_.getAbsolutePath)
        .getOrElse("")

    val contentRoots: Seq[IdeaContentRoot] =
      Option(m.getContentRoots)
        .map(_.asScala.toSeq)
        .getOrElse(Seq.empty)
    def relativize(p: Path): String = {
      config.projectDir.relativize(p).toString
    }
    val sourceDirs = contentRoots
      .flatMap { cr =>
        Option(cr.getSourceDirectories)
          .map(_.asScala.toSeq)
          .getOrElse(Seq.empty)
          .map(_.getDirectory.toPath())
      }
      .distinct
      .sorted
      .map(relativize)

    val testSourceDirs = contentRoots
      .flatMap { cr =>
        Option(cr.getTestDirectories)
          .map(_.asScala.toSeq)
          .getOrElse(Seq.empty)
          .map(_.getDirectory.toPath())
      }
      .distinct
      .sorted
      .map(relativize)

    val javaSettings = Option(m.getJavaLanguageSettings)
    val javaSource =
      javaSettings.flatMap(s => Option(s.getLanguageLevel)).map(_.toString)
    val javaTarget = javaSettings
      .flatMap(s => Option(s.getTargetBytecodeVersion))
      .map(_.toString)
    val classDirectories: Seq[String] =
      sourceSetsMap.get(projectPath) match {
        case Some(dirs) if dirs.classDirectories.nonEmpty =>
          dirs.classDirectories.map(d => relativize(java.nio.file.Paths.get(d)))
        case _ =>
          val ideaDir =
            Option(m.getCompilerOutput)
              .flatMap(output => Option(output.getOutputDir))
              .map(_.toPath)
          ideaDir.map(relativize).toSeq
      }
    val testClassDirectory: Seq[String] =
      sourceSetsMap
        .get(projectPath)
        .map(
          _.testClassDirectory.map(d => relativize(java.nio.file.Paths.get(d)))
        )
        .getOrElse(Nil)

    val testFixturesClassDirectories: Seq[String] =
      sourceSetsMap
        .get(projectPath)
        .map(
          _.testFixturesClassDirectories
            .map(d => relativize(java.nio.file.Paths.get(d)))
        )
        .getOrElse(Nil)

    val testFixturesSources: Seq[String] =
      sourceSetsMap
        .get(projectPath)
        .map(
          _.testFixturesSources.map(d => relativize(java.nio.file.Paths.get(d)))
        )
        .getOrElse(Nil)

    val testFixturesProjectDeps: Seq[String] =
      sourceSetsMap
        .get(projectPath)
        .map(_.testFixturesProjectDeps)
        .getOrElse(Nil)

    val (externalDeps, projectDeps) = classifyDependencies(m)

    ModuleReport(
      name = m.getName,
      projectPath = projectPath,
      projectDir = projectDir,
      description =
        Option(gradleProject).flatMap(p => Option(p.getDescription)),
      javaSourceLevel = javaSource,
      javaTargetLevel = javaTarget,
      classDirectories = classDirectories,
      testClassDirectory = testClassDirectory,
      sourceDirectories = sourceDirs,
      testSourceDirectories = testSourceDirs,
      externalDependencies =
        externalDeps.sortBy(d => (d.group, d.name, d.version, d.scope)),
      projectDependencies = projectDeps.sortBy(d => (d.targetModule, d.scope)),
      testFixturesSources = testFixturesSources,
      testFixturesClassDirectories = testFixturesClassDirectories,
      testFixturesProjectDeps = testFixturesProjectDeps,
    )
  }

  /**
   * Rewrite a Gradle project path to be relative to the composite build root.
   * For included-build modules we prefix the path with the included build's name
   * so that Gradle can resolve the task from the composite root, e.g.
   * `":"` → `"plugin-lib:"` and `":server"` → `"plugin-lib:server"`.
   */
  private def prefixProjectPath(path: String, buildName: String): String =
    if (path == ":" || path.isEmpty) s"$buildName:"
    else s"$buildName:${path.stripPrefix(":")}"

  private def classifyDependencies(
      m: IdeaModule
  ): (Seq[ExternalDependency], Seq[ProjectDependency]) = {
    val all: Seq[IdeaDependency] =
      Option(m.getDependencies).map(_.asScala.toSeq).getOrElse(Seq.empty)
    val externals = all.collect { case d: IdeaSingleEntryLibraryDependency =>
      val gav = Option(d.getGradleModuleVersion)
      val file = Option(d.getFile)
      val source = Option(d.getSource)
      ExternalDependency(
        group = gav.map(_.getGroup),
        name = gav
          .map(_.getName)
          .orElse(file.map(stripJarExtension)),
        version = gav.map(_.getVersion),
        scope = scopeOf(d),
        file = file.map(_.toPath().toUri().toString()),
        source = source.map(_.toPath().toUri().toString()),
      )
    }

    val projects = all.collect { case d: IdeaModuleDependency =>
      ProjectDependency(
        targetModule = Option(d.getTargetModuleName).getOrElse("<unknown>"),
        scope = scopeOf(d),
      )
    }

    (externals, projects)
  }
  private def scopeOf(d: IdeaDependency): String =
    Option(d.getScope).flatMap(s => Option(s.getScope)).getOrElse("COMPILE")

  private def stripJarExtension(f: File): String = {
    val n = f.getName
    if (n.endsWith(".jar")) n.dropRight(4) else n
  }
}
