package scala.meta.internal.metals.mbt.importer.bazel

import java.nio.file.Files
import java.nio.file.Path

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.mbt.MbtDependencyModule
import scala.meta.internal.metals.mbt.importer.BazelMavenJsonImporter.ScannedExtDir
import scala.meta.io.AbsolutePath

import com.google.gson.JsonObject

object MavenLockFileParser {
  def create(json: JsonObject): Option[MavenLockFileParser] = {
    Option(json.getAsJsonObject("artifacts")) match {
      case Some(artifacts) =>
        scribe.debug("Using the v5 parser for the rules_jvm_external lock file")
        Some(new V5LockFileParser(artifacts))
      case None =>
        Option(json.getAsJsonObject("dependency_tree"))
          .flatMap(dt => Option(dt.getAsJsonArray("dependencies"))) match {
          case Some(dependencies) =>
            scribe.debug(
              "Using the legacy (v4 and earlier) parser for the rules_jvm_external lock file"
            )
            Some(new LegacyLockFileParser(dependencies))
          case None =>
            // Unknown format; possibly a newer version, or not a lock file at all
            scribe.warn(
              "Could not determine the format of rules_jvm_external lock file; skipping"
            )
            None
        }
    }
  }
}

trait MavenLockFileParser {
  def parse(
      repositoryNames: Seq[String],
      extDirs: Seq[ScannedExtDir],
  ): Seq[MbtDependencyModule]

  /**
   * Find JARs inside Bzlmod maven repository roots such as:
   * rules_jvm_external++maven+unpinned_maven/v1/https/...
   */
  protected def findInExternalRepositories(
      scanned: ScannedExtDir,
      relativePaths: Seq[String],
      repositoryNames: Seq[String],
  ): Option[AbsolutePath] = {
    val directMatch =
      relativePaths.map(path => scanned.dir.resolve(path)).find(_.exists)

    directMatch.orElse {
      val bzlmodRepositoryNames =
        repositoryNames.flatMap(repo => Seq(repo, s"unpinned_$repo")).distinct

      val candidateRepositories = scanned.entries.filter { entry =>
        val name = entry.filename.toString
        entry.isDirectory &&
        name.startsWith("rules_jvm_external") &&
        bzlmodRepositoryNames.exists { repo =>
          name.endsWith(s"+$repo") || name.endsWith(s"~$repo")
        }
      }

      val repositoryRelativePaths = relativePaths.flatMap { path =>
        path +: bzlmodRepositoryNames.map(repo => path.stripPrefix(s"$repo/"))
      }.distinct

      val found = candidateRepositories.flatMap { repo =>
        repositoryRelativePaths
          .map(path => repo.resolve(path))
          .find(_.exists)
      }.headOption

      if (candidateRepositories.nonEmpty && found.isEmpty) {
        scribe.warn(
          s"JAR not found in central maven repos " +
            s"${candidateRepositories.map(_.filename).mkString(", ")}; " +
            s"tried: [${repositoryRelativePaths.mkString(", ")}]"
        )
      }

      found
    }
  }

  /**
   * Find the JAR file in the Bazel external directory.
   *
   * Bazel stores external dependencies in different patterns:
   *
   * Bzlmod mode (Bazel 6+):
   *   {output_base}/external/rules_jvm_external~{version}~maven~{artifact_dir}_{version}/file/v1/{group_path}/{artifact}/{version}/{artifact}-{version}.jar
   *   Example: rules_jvm_external~6.2~maven~com_google_guava_guava_32_1_3_android/file/v1/com/google/guava/guava/32.1.3-android/guava-32.1.3-android.jar
   *
   * WORKSPACE mode:
   *   {output_base}/external/maven/v1/https/repo1.maven.org/maven2/{group_path}/{artifact}/{version}/{artifact}-{version}.jar
   */
  protected def findJarPath(
      groupId: String,
      artifactId: String,
      version: String,
      repositoryNames: Seq[String],
      extDirs: Seq[ScannedExtDir],
  ): Option[String] = {
    val groupPath = groupId.replace('.', '/')
    val jarName = s"$artifactId-$version.jar"

    val bzlmodArtifactDir = toBzlmodArtifactDir(groupId, artifactId, version)

    val workspacePatterns =
      workspaceArtifactPatterns(
        repositoryNames,
        groupPath,
        artifactId,
        version,
        jarName,
      )

    extDirs.view
      .flatMap { scanned =>
        findBzlmodJar(
          scanned,
          bzlmodArtifactDir,
          groupPath,
          artifactId,
          version,
          jarName,
        )
          .orElse(
            findInExternalRepositories(
              scanned,
              workspacePatterns,
              repositoryNames,
            )
          )
      }
      .headOption
      .orElse {
        val m2Path = Path.of(
          System.getProperty("user.home"),
          ".m2",
          "repository",
          groupPath,
          artifactId,
          version,
          jarName,
        )
        if (Files.exists(m2Path)) Some(AbsolutePath(m2Path)) else None
      }
      .map(_.toURI.toString)
  }

  /**
   * Find the sources JAR file.
   */
  protected def findSourcesPath(
      groupId: String,
      artifactId: String,
      version: String,
      repositoryNames: Seq[String],
      extDirs: Seq[ScannedExtDir],
  ): Option[String] = {
    val groupPath = groupId.replace('.', '/')
    val sourcesJarName = s"$artifactId-$version-sources.jar"

    val bzlmodSourcesDirs = toBzlmodSourcesDirs(groupId, artifactId, version)

    val workspacePatterns =
      workspaceArtifactPatterns(
        repositoryNames,
        groupPath,
        artifactId,
        version,
        sourcesJarName,
      )

    extDirs.view
      .flatMap { scanned =>
        bzlmodSourcesDirs.view
          .flatMap(
            findBzlmodJar(
              scanned,
              _,
              groupPath,
              artifactId,
              version,
              sourcesJarName,
            )
          )
          .headOption
          .orElse(
            findInExternalRepositories(
              scanned,
              workspacePatterns,
              repositoryNames,
            )
          )
      }
      .headOption
      .orElse {
        // Fall back to local Maven cache
        val m2Path = Path.of(
          System.getProperty("user.home"),
          ".m2",
          "repository",
          groupPath,
          artifactId,
          version,
          sourcesJarName,
        )
        if (Files.exists(m2Path)) Some(AbsolutePath(m2Path)) else None
      }
      .map(_.toURI.toString)
  }

  private def workspaceArtifactPatterns(
      repositoryNames: Seq[String],
      groupPath: String,
      artifactId: String,
      version: String,
      fileName: String,
  ): Seq[String] =
    (repositoryNames.map(repo =>
      s"$repo/v1/https/repo1.maven.org/maven2/$groupPath/$artifactId/$version/$fileName"
    ) ++ Seq(
      s"maven/v1/https/repo1.maven.org/maven2/$groupPath/$artifactId/$version/$fileName",
      s"maven/$groupPath/$artifactId/$version/$fileName",
    )).distinct

  /**
   * Find JAR in Bzlmod external directory by scanning for rules_jvm_external prefix.
   *
   * Supports multiple Bazel versions:
   * - Bazel 7.x: rules_jvm_external~{version}~maven~{artifactDir}
   * - Bazel 8.x: rules_jvm_external++maven+{artifactDir}
   */
  private def findBzlmodJar(
      scanned: ScannedExtDir,
      artifactDir: String,
      groupPath: String,
      artifactId: String,
      version: String,
      jarName: String,
  ): Option[AbsolutePath] = {
    // Look for directory matching Bazel 7.x pattern: rules_jvm_external~{version}~maven~{artifactDir}
    val bazel7Dir = scanned.entries.find { entry =>
      val name = entry.filename.toString
      name.startsWith("rules_jvm_external~") &&
      name.contains("~maven~") &&
      name.endsWith(s"~$artifactDir")
    }

    // Look for directory matching Bazel 8.x pattern: rules_jvm_external++maven+{artifactDir}
    val bazel8Dir = scanned.entries.find { entry =>
      val name = entry.filename.toString
      name.startsWith("rules_jvm_external++maven+") &&
      name.endsWith(s"+$artifactDir")
    }

    val matchingDir = bazel7Dir.orElse(bazel8Dir)

    matchingDir.flatMap { dir =>
      val jarPath =
        dir.resolve(s"file/v1/$groupPath/$artifactId/$version/$jarName")
      if (jarPath.exists) {
        scribe.debug(s"Found Bzlmod JAR: $jarPath")
        Some(jarPath)
      } else {
        // Also try without the nested path (some artifacts are directly in the directory)
        val altJarPath = dir.resolve(s"file/$jarName")
        if (altJarPath.exists) {
          scribe.debug(s"Found Bzlmod JAR (alt): $altJarPath")
          Some(altJarPath)
        } else {
          None
        }
      }
    }
  }

  /**
   * Convert artifact coordinates to Bzlmod sources directory name formats.
   * Example: com.google.guava:guava:32.1.3-android -> com_google_guava_guava_sources_32_1_3_android
   */
  private def toBzlmodSourcesDirs(
      groupId: String,
      artifactId: String,
      version: String,
  ): Seq[String] = {
    val sanitize = (s: String) =>
      s.replace('.', '_').replace('-', '_').replace(':', '_')
    Seq(
      s"${sanitize(groupId)}_${sanitize(artifactId)}_sources_${sanitize(version)}",
      s"${sanitize(groupId)}_${sanitize(artifactId)}_jar_sources_${sanitize(version)}",
    )
  }

  /**
   * Convert artifact coordinates to Bzlmod directory name format.
   * Example: com.google.guava:guava:32.1.3-android -> com_google_guava_guava_32_1_3_android
   */
  private def toBzlmodArtifactDir(
      groupId: String,
      artifactId: String,
      version: String,
  ): String = {
    val sanitize = (s: String) =>
      s.replace('.', '_').replace('-', '_').replace(':', '_')
    s"${sanitize(groupId)}_${sanitize(artifactId)}_${sanitize(version)}"
  }

}
