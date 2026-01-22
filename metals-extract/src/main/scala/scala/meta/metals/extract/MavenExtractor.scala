package scala.meta.metals.extract

import java.io.FileReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.{ArrayList => JArrayList}
import java.util.{List => JList}

import scala.jdk.CollectionConverters._
import scala.util.Try
import scala.util.Using

import org.apache.maven.model.{Dependency => MavenDependency}
import org.apache.maven.model.Model
import org.apache.maven.model.io.xpp3.MavenXpp3Reader
import org.apache.maven.repository.internal.MavenRepositorySystemUtils
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.artifact.Artifact
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.collection.CollectRequest
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory
import org.eclipse.aether.graph.{Dependency => AetherDependency}
import org.eclipse.aether.repository.LocalRepository
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.resolution.ArtifactRequest
import org.eclipse.aether.resolution.ArtifactResult
import org.eclipse.aether.resolution.DependencyRequest
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory
import org.eclipse.aether.spi.connector.transport.TransporterFactory
import org.eclipse.aether.transport.file.FileTransporterFactory
import org.eclipse.aether.transport.http.HttpTransporterFactory
import org.eclipse.aether.util.artifact.SubArtifact

/**
 * Extracts dependencies from Maven projects using the Maven Artifact Resolver API (Eclipse Aether).
 */
object MavenExtractor {

  private val m2Repo: Path = {
    val m2RepoEnv = sys.env.get("M2_REPO")
    m2RepoEnv match {
      case Some(path) => Path.of(path)
      case None => Path.of(System.getProperty("user.home"), ".m2", "repository")
    }
  }

  def extract(
      projectDir: Path,
      resolveSources: Boolean,
      verbose: Boolean
  ): Either[String, Seq[DependencyModule]] = {
    val pomFile = projectDir.resolve("pom.xml")

    if (!Files.exists(pomFile)) {
      return Left(s"pom.xml not found in $projectDir")
    }

    Try {
      val model = readPom(pomFile)
      val repoSystem = newRepositorySystem()
      val session = newSession(repoSystem)

      // Collect all dependencies from the model
      val dependencies = collectDependencies(model, projectDir, verbose)

      if (verbose) {
        println(s"Found ${dependencies.size} direct dependencies")
      }

      // Create repository list (Maven Central + any configured repos)
      val repositories = getRepositories(model)

      // Resolve all dependencies transitively
      val resolved = resolveDependencies(repoSystem, session, dependencies, repositories, verbose)

      if (verbose) {
        println(s"Resolved ${resolved.size} total artifacts (including transitive)")
      }

      // Convert to DependencyModule format
      val modules = resolved.flatMap { artifactResult =>
        val artifact = artifactResult.getArtifact
        if (artifact != null && artifact.getFile != null) {
          val jarPath = artifact.getFile.getAbsolutePath

          // Skip SNAPSHOT versions
          if (artifact.getVersion.contains("SNAPSHOT")) {
            if (verbose) println(s"  Skipping SNAPSHOT: ${artifact}")
            None
          } else {
            val sourcesPath = if (resolveSources) {
              resolveSourcesJar(repoSystem, session, artifact, repositories, verbose)
            } else None

            Some(DependencyModule(
              id = s"${artifact.getGroupId}:${artifact.getArtifactId}:${artifact.getVersion}",
              jar = jarPath,
              sources = sourcesPath
            ))
          }
        } else {
          None
        }
      }.distinctBy(_.id)

      modules
    }.toEither.left.map(e => s"Maven extraction failed: ${e.getMessage}")
  }

  private def readPom(pomFile: Path): Model = {
    Using.resource(new FileReader(pomFile.toFile)) { reader =>
      new MavenXpp3Reader().read(reader)
    }
  }

  private def newRepositorySystem(): RepositorySystem = {
    val locator = MavenRepositorySystemUtils.newServiceLocator()
    locator.addService(classOf[RepositoryConnectorFactory], classOf[BasicRepositoryConnectorFactory])
    locator.addService(classOf[TransporterFactory], classOf[FileTransporterFactory])
    locator.addService(classOf[TransporterFactory], classOf[HttpTransporterFactory])
    locator.getService(classOf[RepositorySystem])
  }

  private def newSession(system: RepositorySystem): RepositorySystemSession = {
    val session = MavenRepositorySystemUtils.newSession()
    val localRepo = new LocalRepository(m2Repo.toFile)
    session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, localRepo))
    session
  }

  private def collectDependencies(model: Model, projectDir: Path, verbose: Boolean): Seq[AetherDependency] = {
    // Get managed dependencies from dependencyManagement (including from parent)
    val managedVersions = collectManagedVersions(model, projectDir, verbose)

    // First, collect all Maven model dependencies from this POM and parent POMs
    val directMavenDeps = collectMavenDependencies(model, projectDir, verbose)

    // Also collect dependencies from all submodules (for multi-module projects)
    val moduleDeps = collectModuleDependencies(model, projectDir, managedVersions, verbose)

    // Convert direct Maven dependencies to Aether dependencies
    val directAetherDeps = directMavenDeps.flatMap { mavenDep =>
      convertToAetherDependency(mavenDep, managedVersions, model, verbose)
    }

    // Combine and deduplicate
    (directAetherDeps ++ moduleDeps).distinctBy(d => (d.getArtifact.getGroupId, d.getArtifact.getArtifactId))
  }

  private def collectManagedVersions(model: Model, projectDir: Path, verbose: Boolean): Map[(String, String), String] = {
    // Get managed versions from parent POM first
    val parentManagedVersions = Option(model.getParent).flatMap { parent =>
      val relativePath = Option(parent.getRelativePath).getOrElse("../pom.xml")
      val parentPomPath = projectDir.resolve(relativePath)
      if (Files.exists(parentPomPath)) {
        Try(readPom(parentPomPath)).toOption.map { parentModel =>
          collectManagedVersions(parentModel, parentPomPath.getParent, verbose)
        }
      } else {
        None
      }
    }.getOrElse(Map.empty)

    // Get managed dependencies from this model's dependencyManagement
    val localManagedDeps = Option(model.getDependencyManagement)
      .flatMap(dm => Option(dm.getDependencies))
      .map(_.asScala.toSeq)
      .getOrElse(Seq.empty)

    val localManagedVersions: Map[(String, String), String] = localManagedDeps.map { d =>
      (d.getGroupId, d.getArtifactId) -> d.getVersion
    }.toMap

    // Local versions override parent versions
    parentManagedVersions ++ localManagedVersions
  }

  private def collectModuleDependencies(
      model: Model,
      projectDir: Path,
      managedVersions: Map[(String, String), String],
      verbose: Boolean
  ): Seq[AetherDependency] = {
    // Collect properties from parent chain for resolution
    val parentProperties = collectProperties(model, projectDir)

    val modules = Option(model.getModules).map(_.asScala.toSeq).getOrElse(Seq.empty)

    if (modules.nonEmpty && verbose) {
      println(s"Processing ${modules.size} submodules...")
    }

    modules.flatMap { modulePath =>
      val modulePomPath = projectDir.resolve(modulePath).resolve("pom.xml")
      if (Files.exists(modulePomPath)) {
        Try {
          if (verbose) println(s"  Reading module: $modulePath")
          val moduleModel = readPom(modulePomPath)
          val moduleDir = modulePomPath.getParent

          // Recursively collect from this module (including its submodules)
          val moduleManagedVersions = collectManagedVersions(moduleModel, moduleDir, verbose) ++ managedVersions
          val moduleMavenDeps = collectMavenDependencies(moduleModel, moduleDir, verbose)

          // Merge properties from parent into module for resolution
          val moduleProperties = parentProperties ++ collectProperties(moduleModel, moduleDir)

          val moduleAetherDeps = moduleMavenDeps.flatMap { mavenDep =>
            convertToAetherDependencyWithProps(mavenDep, moduleManagedVersions, moduleModel, moduleProperties, verbose)
          }

          // Also process nested modules
          val nestedDeps = collectModuleDependencies(moduleModel, moduleDir, moduleManagedVersions, verbose)

          moduleAetherDeps ++ nestedDeps
        }.recover { case e =>
          if (verbose) println(s"  Warning: Failed to read module $modulePath: ${e.getMessage}")
          Seq.empty[AetherDependency]
        }.get
      } else {
        if (verbose) println(s"  Warning: Module pom.xml not found: $modulePomPath")
        Seq.empty
      }
    }
  }

  private def collectProperties(model: Model, projectDir: Path): Map[String, String] = {
    // Get properties from parent first
    val parentProps = Option(model.getParent).flatMap { parent =>
      val relativePath = Option(parent.getRelativePath).getOrElse("../pom.xml")
      val parentPomPath = projectDir.resolve(relativePath)
      if (Files.exists(parentPomPath)) {
        Try(readPom(parentPomPath)).toOption.map { parentModel =>
          collectProperties(parentModel, parentPomPath.getParent)
        }
      } else {
        None
      }
    }.getOrElse(Map.empty)

    // Get local properties
    val localProps = Option(model.getProperties).map { props =>
      props.stringPropertyNames().asScala.map(k => k -> props.getProperty(k)).toMap
    }.getOrElse(Map.empty)

    // Add built-in properties
    val builtinProps = Map(
      "project.version" -> Option(model.getVersion).getOrElse(""),
      "project.groupId" -> Option(model.getGroupId).getOrElse(""),
      "project.artifactId" -> Option(model.getArtifactId).getOrElse("")
    ).filter(_._2.nonEmpty)

    parentProps ++ localProps ++ builtinProps
  }

  private def collectMavenDependencies(model: Model, projectDir: Path, verbose: Boolean): Seq[MavenDependency] = {
    // Handle parent POM if present
    val parentDeps = Option(model.getParent).flatMap { parent =>
      val relativePath = Option(parent.getRelativePath).getOrElse("../pom.xml")
      val parentPomPath = projectDir.resolve(relativePath)
      if (Files.exists(parentPomPath)) {
        if (verbose) println(s"Reading parent POM from $parentPomPath")
        Try(readPom(parentPomPath)).toOption.map { parentModel =>
          collectMavenDependencies(parentModel, parentPomPath.getParent, verbose)
        }
      } else {
        None
      }
    }.getOrElse(Seq.empty)

    // Get dependencies from this model
    val directDeps: Seq[MavenDependency] = Option(model.getDependencies)
      .map(_.asScala.toSeq)
      .getOrElse(Seq.empty)

    parentDeps ++ directDeps
  }

  private def convertToAetherDependency(
      mavenDep: MavenDependency,
      managedVersions: Map[(String, String), String],
      model: Model,
      verbose: Boolean
  ): Option[AetherDependency] = {
    val properties = collectProperties(model, Path.of("."))
    convertToAetherDependencyWithProps(mavenDep, managedVersions, model, properties, verbose)
  }

  private def convertToAetherDependencyWithProps(
      mavenDep: MavenDependency,
      managedVersions: Map[(String, String), String],
      @annotation.nowarn("msg=never used") model: Model,
      properties: Map[String, String],
      verbose: Boolean
  ): Option[AetherDependency] = {
    // Skip test and provided scope by default
    val scope = Option(mavenDep.getScope).getOrElse("compile")
    if (scope == "test" || scope == "provided" || scope == "system") {
      return None
    }

    val version = Option(mavenDep.getVersion)
      .orElse(managedVersions.get((mavenDep.getGroupId, mavenDep.getArtifactId)))
      .getOrElse {
        if (verbose) println(s"  Warning: No version for ${mavenDep.getGroupId}:${mavenDep.getArtifactId}")
        return None
      }

    // Handle property placeholders like ${project.version} using collected properties
    val resolvedVersion = resolvePropertiesWithMap(version, properties)

    if (resolvedVersion.startsWith("$")) {
      if (verbose) println(s"  Warning: Unresolved property in version: $resolvedVersion")
      return None
    }

    val classifier = Option(mavenDep.getClassifier).filter(_.nonEmpty)
    val packaging = Option(mavenDep.getType).getOrElse("jar")

    val artifact = classifier match {
      case Some(c) =>
        new DefaultArtifact(mavenDep.getGroupId, mavenDep.getArtifactId, c, packaging, resolvedVersion)
      case None =>
        new DefaultArtifact(mavenDep.getGroupId, mavenDep.getArtifactId, packaging, resolvedVersion)
    }

    Some(new AetherDependency(artifact, scope))
  }

  private def resolvePropertiesWithMap(value: String, properties: Map[String, String]): String = {
    val propertyPattern = """\$\{([^}]+)\}""".r
    propertyPattern.replaceAllIn(value, { m =>
      val propName = m.group(1)
      properties.getOrElse(propName, s"$${$propName}")
    })
  }

  private def getRepositories(model: Model): JList[RemoteRepository] = {
    val repos = new JArrayList[RemoteRepository]()

    // Always include Maven Central
    repos.add(new RemoteRepository.Builder(
      "central", "default", "https://repo1.maven.org/maven2/"
    ).build())

    // Add repositories defined in the POM
    Option(model.getRepositories).foreach { modelRepos =>
      modelRepos.asScala.foreach { repo =>
        repos.add(new RemoteRepository.Builder(
          repo.getId, "default", repo.getUrl
        ).build())
      }
    }

    repos
  }

  private def resolveDependencies(
      system: RepositorySystem,
      session: RepositorySystemSession,
      dependencies: Seq[AetherDependency],
      repositories: JList[RemoteRepository],
      verbose: Boolean
  ): Seq[ArtifactResult] = {
    if (dependencies.isEmpty) {
      return Seq.empty
    }

    val collectRequest = new CollectRequest()
    dependencies.foreach(d => collectRequest.addDependency(d))
    collectRequest.setRepositories(repositories)

    val dependencyRequest = new DependencyRequest(collectRequest, null)

    try {
      val result = system.resolveDependencies(session, dependencyRequest)
      result.getArtifactResults.asScala.toSeq
    } catch {
      case e: Exception =>
        if (verbose) {
          println(s"  Warning: Some dependencies could not be resolved: ${e.getMessage}")
        }
        // Try to resolve individually to get partial results
        dependencies.flatMap { dep =>
          Try {
            val request = new ArtifactRequest(dep.getArtifact, repositories, null)
            system.resolveArtifact(session, request)
          }.toOption
        }
    }
  }

  private def resolveSourcesJar(
      system: RepositorySystem,
      session: RepositorySystemSession,
      artifact: Artifact,
      repositories: JList[RemoteRepository],
      verbose: Boolean
  ): Option[String] = {
    val sourcesArtifact = new SubArtifact(artifact, "sources", "jar")
    val request = new ArtifactRequest(sourcesArtifact, repositories, null)

    Try {
      val result = system.resolveArtifact(session, request)
      if (result.getArtifact != null && result.getArtifact.getFile != null) {
        Some(result.getArtifact.getFile.getAbsolutePath)
      } else {
        None
      }
    }.getOrElse {
      if (verbose) {
        println(s"  No sources jar for ${artifact.getGroupId}:${artifact.getArtifactId}:${artifact.getVersion}")
      }
      None
    }
  }
}
