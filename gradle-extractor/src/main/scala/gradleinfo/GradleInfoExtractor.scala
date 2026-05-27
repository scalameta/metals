package gradleinfo

import java.io.File
import java.nio.file.Path

import scala.jdk.CollectionConverters._

import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ModelBuilder
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.model.build.BuildEnvironment
import org.gradle.tooling.model.idea.IdeaContentRoot
import org.gradle.tooling.model.idea.IdeaDependency
import org.gradle.tooling.model.idea.IdeaModule
import org.gradle.tooling.model.idea.IdeaModuleDependency
import org.gradle.tooling.model.idea.IdeaProject
import org.gradle.tooling.model.idea.IdeaSingleEntryLibraryDependency

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

  def extract(config: ExtractorConfig): ProjectReport = {
    val absRoot = config.projectDir.toFile.getCanonicalFile()
    require(absRoot.isDirectory, s"Not a directory: $absRoot")

    val connector = GradleConnector.newConnector().forProjectDirectory(absRoot)
    config.gradleVersion.foreach(connector.useGradleVersion)
    config.gradleInstallation.foreach(connector.useInstallation)
    config.gradleUserHome.foreach(connector.useGradleUserHomeDir)

    val connection: ProjectConnection = connector.connect()
    try {
      val env = fetchModel(connection, classOf[BuildEnvironment], config)
      val idea = fetchModel(connection, classOf[IdeaProject], config)

      val modules: Seq[ModuleReport] =
        idea.getModules.asScala.toSeq.map(extractModule(_, config))

      ProjectReport(
        rootName = idea.getName,
        rootDir = absRoot.getAbsolutePath,
        gradleVersion = env.getGradle.getGradleVersion,
        javaHome = env.getJava.getJavaHome.toPath().toUri().toString(),
        modules = modules.sortBy(_.projectPath),
      )
    } finally connection.close()
  }

  /**
   * Build a model with all per-operation Tooling API settings applied
   * (currently just the optional Java home for the daemon).
   */
  private def fetchModel[T](
      connection: ProjectConnection,
      modelType: Class[T],
      config: ExtractorConfig,
  ): T = {
    val builder: ModelBuilder[T] = connection.model(modelType)
    config.gradleJvm.foreach(builder.setJavaHome)
    builder.get()
  }

  private def extractModule(
      m: IdeaModule,
      config: ExtractorConfig,
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
    val classDirectory =
      Option(m.getCompilerOutput)
        .flatMap(output => Option(output.getOutputDir))
        .map(_.toPath)
        .map(relativize)

    val (externalDeps, projectDeps) = classifyDependencies(m)

    ModuleReport(
      name = m.getName,
      projectPath = projectPath,
      projectDir = projectDir,
      description =
        Option(gradleProject).flatMap(p => Option(p.getDescription)),
      javaSourceLevel = javaSource,
      javaTargetLevel = javaTarget,
      classDirectory = classDirectory,
      sourceDirectories = sourceDirs,
      testSourceDirectories = testSourceDirs,
      externalDependencies =
        externalDeps.sortBy(d => (d.group, d.name, d.version, d.scope)),
      projectDependencies = projectDeps.sortBy(d => (d.targetModule, d.scope)),
    )
  }
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
