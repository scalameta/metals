package scala.meta.internal.metals.mbt.importer.bazel

import java.nio.file.Files
import java.nio.file.Path

import scala.util.Try

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.mbt.MbtDependencyModule
import scala.meta.internal.metals.mbt.importer.BazelMavenJsonImporter.ScannedExtDir

import com.google.gson.JsonArray
import com.google.gson.JsonElement

/**
 * Legacy format (rules_jvm_external v4 and earlier):
 * <pre>
 * {
 *   "dependency_tree": {
 *     "dependencies": [
 *       { "coord": "com.google.guava:guava:31.1-jre", "file": "v1/https/..." }
 *     ]
 *   }
 * }
 * </pre>
 */
class LegacyLockFileParser private[bazel] (dependencies: JsonArray)
    extends MavenLockFileParser {

  override def parse(
      repositoryNames: Seq[String],
      extDirs: Seq[ScannedExtDir],
  ): Seq[MbtDependencyModule] = {
    val deps = dependencies.iterator().asScala.toSeq

    // Group by base coordinate to pair JARs with sources
    val jarDeps = deps.filter { elem =>
      if (!elem.isJsonObject) false
      else {
        val coord = Option(elem.getAsJsonObject.get("coord"))
          .filter(_.isJsonPrimitive)
          .map(_.getAsString)
          .getOrElse("")
        !coord.contains(":jar:sources:") && !coord.contains("-sources.")
      }
    }

    jarDeps.flatMap { elem =>
      parseLegacyDependency(elem, deps, repositoryNames, extDirs)
    }
  }

  private def parseLegacyDependency(
      elem: JsonElement,
      allDeps: Seq[JsonElement],
      repositoryNames: Seq[String],
      extDirs: Seq[ScannedExtDir],
  ): Option[MbtDependencyModule] = {
    Try {
      val obj = elem.getAsJsonObject
      val coord =
        obj
          .get("coord")
          .getAsString // e.g., "com.google.guava:guava:31.1-jre"
      val file = Option(obj.get("file")).map(_.getAsString)
      val url = Option(obj.get("url")).map(_.getAsString)

      // Parse coordinate: group:artifact[:packaging[:classifier]]:version
      val parts = coord.split(":")
      if (parts.length < 3) None
      else {
        val (groupId, artifactId, version) = (parts(0), parts(1), parts.last)

        if (version.contains("SNAPSHOT")) {
          scribe.debug(s"Skipping SNAPSHOT: $coord")
          None
        } else {
          val id = s"$groupId:$artifactId:$version"
          val groupPath = groupId.replace('.', '/')
          val jarName = s"$artifactId-$version.jar"

          // Find JAR path - try local paths first
          val jarPath = file
            .flatMap { f =>
              findJarFromLegacyPath(f, repositoryNames, extDirs)
            }
            .orElse {
              findJarPath(
                groupId,
                artifactId,
                version,
                repositoryNames,
                extDirs,
              )
            }
            .orElse {
              scribe.warn(
                s"Downloading from URL, could not find locally: $url"
              )
              url.flatMap { u =>
                downloadToM2Cache(u, groupPath, artifactId, version, jarName)
              }
            }

          jarPath.map { jar =>
            // Look for corresponding sources in the dependencies array
            val sourcesCoord = s"$groupId:$artifactId:jar:sources:$version"
            val sourcesEntry = allDeps.find { e =>
              e.isJsonObject &&
              Option(e.getAsJsonObject.get("coord"))
                .map(_.getAsString)
                .contains(sourcesCoord)
            }

            val sourcesFile = sourcesEntry.flatMap { e =>
              Option(e.getAsJsonObject.get("file")).map(_.getAsString)
            }
            val sourcesUrl = sourcesEntry.flatMap { e =>
              Option(e.getAsJsonObject.get("url")).map(_.getAsString)
            }

            val sourcesJarName = s"$artifactId-$version-sources.jar"
            val sourcesPath = sourcesFile
              .flatMap { f =>
                findJarFromLegacyPath(f, repositoryNames, extDirs)
              }
              .orElse {
                findSourcesPath(
                  groupId,
                  artifactId,
                  version,
                  repositoryNames,
                  extDirs,
                )
              }
              .orElse {
                // Download sources from URL if available
                sourcesUrl.flatMap { u =>
                  downloadToM2Cache(
                    u,
                    groupPath,
                    artifactId,
                    version,
                    sourcesJarName,
                  )
                }
              }

            MbtDependencyModule(
              id = id,
              jar = jar,
              sources = sourcesPath.orNull,
            )
          }
        }
      }
    }.toOption.flatten
  }

  /**
   * Find JAR from legacy format's "file" path.
   * The file path is like: v1/https/repo1.maven.org/maven2/com/google/guava/guava/31.1-jre/guava-31.1-jre.jar
   */
  private def findJarFromLegacyPath(
      filePath: String,
      repositoryNames: Seq[String],
      extDirs: Seq[ScannedExtDir],
  ): Option[String] = {
    val patterns =
      (repositoryNames.map(repo => s"$repo/$filePath") ++
        Seq(s"maven/$filePath", filePath)).distinct

    extDirs.view
      .flatMap(findInExternalRepositories(_, patterns, repositoryNames))
      .headOption
      .map(_.toURI.toString)
  }

  /**
   * Download a JAR from URL to the local Maven cache.
   */
  private def downloadToM2Cache(
      url: String,
      groupPath: String,
      artifactId: String,
      version: String,
      jarName: String,
  ): Option[String] = {
    Try {
      val m2Dir = Path.of(
        System.getProperty("user.home"),
        ".m2",
        "repository",
        groupPath,
        artifactId,
        version,
      )
      val targetPath = m2Dir.resolve(jarName)

      // Check if already exists
      if (Files.exists(targetPath)) {
        Some(targetPath.toUri.toString)
      } else {
        scribe.debug(s"Downloading: $url")

        val urlLower = url.toLowerCase
        if (
          !urlLower.startsWith("http://") && !urlLower.startsWith("https://")
        ) {
          scribe.debug(
            s"Skipping download: URL must use http or https, got: $url"
          )
          None
        } else {

          // Create directory
          Files.createDirectories(m2Dir)

          // Download file
          val connection = new java.net.URL(url).openConnection()
          connection.setConnectTimeout(10000)
          connection.setReadTimeout(30000)

          val in = connection.getInputStream
          try {
            Files.copy(in, targetPath)
            scribe.debug(s"Downloaded to: $targetPath")
            Some(targetPath.toUri.toString)
          } finally {
            in.close()
          }
        }
      }
    }.toOption.flatten
  }

}
