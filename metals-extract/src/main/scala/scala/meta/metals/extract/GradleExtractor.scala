package scala.meta.metals.extract

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

import scala.jdk.CollectionConverters._
import scala.util.Try
import scala.util.Using

import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.model.eclipse.EclipseProject

/**
 * Extracts dependencies from Gradle projects using the Gradle Tooling API.
 */
object GradleExtractor {

  def extract(
      projectDir: Path,
      resolveSources: Boolean,
      verbose: Boolean
  ): Either[String, Seq[DependencyModule]] = {
    val hasBuildGradle = Files.exists(projectDir.resolve("build.gradle")) ||
      Files.exists(projectDir.resolve("build.gradle.kts"))

    if (!hasBuildGradle) {
      return Left(s"No build.gradle or build.gradle.kts found in $projectDir")
    }

    Try {
      val connector = GradleConnector.newConnector()
        .forProjectDirectory(projectDir.toFile)

      // Use gradle wrapper if available
      val gradlewPath = projectDir.resolve("gradlew")
      if (Files.exists(gradlewPath)) {
        if (verbose) println("Using Gradle wrapper")
        // GradleConnector will automatically detect and use the wrapper
      }

      Using.resource(connector.connect()) { connection =>
        extractFromConnection(connection, resolveSources, verbose)
      }
    }.toEither.left.map(e => s"Gradle extraction failed: ${e.getMessage}")
  }

  private def extractFromConnection(
      connection: ProjectConnection,
      resolveSources: Boolean,
      verbose: Boolean
  ): Seq[DependencyModule] = {
    // Use EclipseProject model which gives us classpath entries with source attachments
    val eclipseProject = connection.getModel(classOf[EclipseProject])

    val modules = collectFromProject(eclipseProject, resolveSources, verbose, Set.empty)

    // Remove duplicates by id
    modules.distinctBy(_.id)
  }

  private def collectFromProject(
      project: EclipseProject,
      resolveSources: Boolean,
      verbose: Boolean,
      visited: Set[String]
  ): Seq[DependencyModule] = {
    val projectPath = project.getProjectDirectory.getAbsolutePath

    if (visited.contains(projectPath)) {
      return Seq.empty
    }

    if (verbose) {
      println(s"Processing Gradle project: ${project.getName}")
    }

    val newVisited = visited + projectPath

    // Get classpath entries from this project
    val entries = project.getClasspath.asScala.toSeq

    val modules = entries.flatMap { entry =>
      Option(entry.getGradleModuleVersion).flatMap { moduleVersion =>
        val group = moduleVersion.getGroup
        val name = moduleVersion.getName
        val version = moduleVersion.getVersion

        // Skip entries without proper coordinates or SNAPSHOT versions
        if (group == null || name == null || version == null) {
          None
        } else if (version.contains("SNAPSHOT")) {
          if (verbose) println(s"  Skipping SNAPSHOT: $group:$name:$version")
          None
        } else {
          val jarFile = entry.getFile
          if (jarFile != null && jarFile.exists()) {
            val sourcesPath = if (resolveSources) {
              Option(entry.getSource).map(_.getAbsolutePath).orElse {
                // Try to find sources in sibling hash directories (Gradle cache structure)
                findSourcesInGradleCache(jarFile, verbose)
              }
            } else None

            Some(DependencyModule(
              id = s"$group:$name:$version",
              jar = jarFile.getAbsolutePath,
              sources = sourcesPath
            ))
          } else {
            None
          }
        }
      }
    }

    // Recursively process child projects
    val childModules = project.getChildren.asScala.toSeq.flatMap { child =>
      collectFromProject(child, resolveSources, verbose, newVisited)
    }

    modules ++ childModules
  }

  /**
   * Gradle cache structure:
   * ~/.gradle/caches/modules-2/files-2.1/group/name/version/HASH/artifact.jar
   *
   * Sources are typically in a sibling hash directory with -sources.jar suffix.
   */
  private def findSourcesInGradleCache(
      jarFile: File,
      @annotation.nowarn("msg=never used") verbose: Boolean
  ): Option[String] = {
    val parent = jarFile.getParentFile // HASH directory
    if (parent == null) return None

    val versionDir = parent.getParentFile // version directory
    if (versionDir == null) return None

    // Look through all hash directories at the same level
    val hashDirs = versionDir.listFiles()
    if (hashDirs == null) return None

    val sourcesJar = hashDirs.toSeq.flatMap { hashDir =>
      if (hashDir.isDirectory) {
        hashDir.listFiles().toSeq.filter { f =>
          f.getName.endsWith("-sources.jar")
        }
      } else {
        Seq.empty
      }
    }.headOption

    sourcesJar.map(_.getAbsolutePath)
  }
}
