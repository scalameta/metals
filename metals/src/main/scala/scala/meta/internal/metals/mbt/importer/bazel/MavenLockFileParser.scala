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

  private def sanitizeBzlmodSegment(s: String): String =
    s.replace('.', '_').replace('-', '_').replace(':', '_')

  /**
   * Convert artifact coordinates to Bzlmod directory name format.
   * Example: com.google.guava:guava:32.1.3-android -> com_google_guava_guava_32_1_3_android
   */
  def toBzlmodArtifactDir(
      groupId: String,
      artifactId: String,
      version: String,
  ): String =
    s"${sanitizeBzlmodSegment(groupId)}_${sanitizeBzlmodSegment(artifactId)}_${sanitizeBzlmodSegment(version)}"

  def toBzlmodSourcesDir(
      groupId: String,
      artifactId: String,
      version: String,
  ): String =
    s"${sanitizeBzlmodSegment(groupId)}_${sanitizeBzlmodSegment(artifactId)}_sources_${sanitizeBzlmodSegment(version)}"

  def toBzlmodJarSourcesDir(
      groupId: String,
      artifactId: String,
      version: String,
  ): String =
    s"${sanitizeBzlmodSegment(groupId)}_${sanitizeBzlmodSegment(artifactId)}_jar_sources_${sanitizeBzlmodSegment(version)}"

  /**
   * Convert artifact coordinates to Bzlmod sources directory name formats.
   * Example: com.google.guava:guava:32.1.3-android -> com_google_guava_guava_sources_32_1_3_android
   */
  def toBzlmodSourcesDirs(
      groupId: String,
      artifactId: String,
      version: String,
  ): Seq[String] =
    Seq(
      toBzlmodSourcesDir(groupId, artifactId, version),
      toBzlmodJarSourcesDir(groupId, artifactId, version),
    )
}

trait MavenLockFileParser {
  final def parse(
      repositoryNames: Seq[String],
      extDirs: Seq[ScannedExtDir],
  ): Seq[MbtDependencyModule] =
    parse(repositoryNames, extDirs, None)

  def parse(
      repositoryNames: Seq[String],
      extDirs: Seq[ScannedExtDir],
      extensionPrefix: Option[String],
  ): Seq[MbtDependencyModule]

  protected def logMissingJars(missingIds: Seq[String]): Unit =
    if (missingIds.nonEmpty) {
      val preview = missingIds.take(5).mkString(", ")
      val more =
        if (missingIds.sizeIs > 5) s" (and ${missingIds.size - 5} more)"
        else ""
      scribe.warn(
        s"bazel-mbt: ${missingIds.size} Maven artifact JAR(s) not found locally: $preview$more"
      )
    }

  /**
   * Find JARs inside Bzlmod maven repository roots such as:
   * rules_jvm_external++maven+unpinned_maven/v1/https/... or
   * +maven_wrapper+unpinned_maven/v1/https/...
   */
  protected def findInExternalRepositories(
      scanned: ScannedExtDir,
      relativePaths: Seq[String],
      repositoryNames: Seq[String],
      extensionPrefix: Option[String] = None,
  ): Option[AbsolutePath] = {
    val directMatch =
      relativePaths.map(path => scanned.dir.resolve(path)).find(_.exists)

    directMatch.orElse {
      val bzlmodRepositoryNames =
        repositoryNames.flatMap(repo => Seq(repo, s"unpinned_$repo")).distinct

      val matchingRepositories =
        for {
          repo <- bzlmodRepositoryNames
          entry <- scanned.bzlmodEntriesBySuffix.getOrElse(repo, Nil)
          if entry.isDirectory
        } yield entry

      val candidateRepositories = extensionPrefix match {
        case Some(prefix) =>
          val (preferred, others) =
            matchingRepositories.partition(_.filename.startsWith(prefix))
          (preferred ++ others).distinct
        case None =>
          matchingRepositories.distinct
      }

      val repositoryRelativePaths = relativePaths.flatMap { path =>
        path +: bzlmodRepositoryNames.map(repo => path.stripPrefix(s"$repo/"))
      }.distinct

      val found = candidateRepositories.collectFirst(
        Function.unlift { repo =>
          repositoryRelativePaths
            .map(path => repo.resolve(path))
            .find(_.exists)
        }
      )

      if (candidateRepositories.nonEmpty && found.isEmpty) {
        scribe.debug(
          s"JAR not found in central maven repos " +
            s"${candidateRepositories.map(_.filename).mkString(", ")}; " +
            s"tried: [${repositoryRelativePaths.mkString(", ")}]"
        )
      }

      found.map { maybeSymlink =>
        // The "unpinned" repository is a symlink to the actual location of the artifacts.
        // If the workspace uses the lock file, this symlink may be removed automatically
        // once the dependencies have been pinned.
        // To ensure the JAR is still found, we need to dealias the symlink.
        val actualPath = maybeSymlink.dealias
        scribe.debug(s"Dealiased $maybeSymlink to $actualPath")
        actualPath
      }
    }
  }

  /**
   * Find the JAR file in the Bazel external directory.
   *
   * Bazel stores external dependencies in different patterns:
   *
   * Bzlmod mode (Bazel 6+):
   *   {output_base}/external/{ext_prefix}{artifact_dir}_{version}/file/v1/{group_path}/{artifact}/{version}/{artifact}-{version}.jar
   *   Examples:
   *   - rules_jvm_external~6.2~maven~com_google_guava_guava_32_1_3_android/file/v1/...
   *   - rules_jvm_external++maven+com_google_guava_guava_33_4_8_jre/file/v1/...
   *   - +maven_wrapper+com_google_guava_guava_33_4_8_jre/file/v1/...
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
      extensionPrefix: Option[String] = None,
  ): Option[String] = {
    val groupPath = groupId.replace('.', '/')
    val jarName = s"$artifactId-$version.jar"

    val bzlmodArtifactDir =
      MavenLockFileParser.toBzlmodArtifactDir(groupId, artifactId, version)

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
          extensionPrefix,
        )
          .orElse(
            findInExternalRepositories(
              scanned,
              workspacePatterns,
              repositoryNames,
              extensionPrefix,
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
      extensionPrefix: Option[String] = None,
  ): Option[String] = {
    val groupPath = groupId.replace('.', '/')
    val sourcesJarName = s"$artifactId-$version-sources.jar"

    val bzlmodSourcesDirs =
      MavenLockFileParser.toBzlmodSourcesDirs(groupId, artifactId, version)

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
              extensionPrefix,
            )
          )
          .headOption
          .orElse(
            findInExternalRepositories(
              scanned,
              workspacePatterns,
              repositoryNames,
              extensionPrefix,
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
   * Find JAR in Bzlmod external directory by matching the trailing repository
   * segment (`+<artifactDir>` in Bazel 8+, `~<artifactDir>` in Bazel 6/7),
   * regardless of whether `rules_jvm_external` was used directly or wrapped by
   * a custom module extension.
   */
  private def findBzlmodJar(
      scanned: ScannedExtDir,
      artifactDir: String,
      groupPath: String,
      artifactId: String,
      version: String,
      jarName: String,
      extensionPrefix: Option[String],
  ): Option[AbsolutePath] = {
    val matches = scanned.bzlmodEntriesBySuffix.getOrElse(artifactDir, Nil)
    val candidates = extensionPrefix match {
      case Some(prefix) =>
        val (preferred, others) =
          matches.partition(_.filename.startsWith(prefix))
        preferred ++ others
      case None =>
        matches
    }

    candidates.collectFirst(
      Function.unlift { dir =>
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
    )
  }

}
