package scala.meta.internal.metals.mbt.importer

import java.nio.file.Files
import java.nio.file.Path

import scala.collection.concurrent.TrieMap
import scala.util.Try

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.mbt.MbtDependencyModule
import scala.meta.io.AbsolutePath

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Extracts dependencies from Bazel projects using rules_jvm_external.
 *
 * This extractor parses the maven_install.json file that rules_jvm_external
 * generates to pin Maven dependencies. It also locates the actual JAR files
 * in the Bazel output base.
 */
object BazelMavenJsonImporter {

  private val gson = new Gson()

  /** Pre-scanned snapshot of an external directory listing. */
  private case class ScannedExtDir(
      dir: AbsolutePath,
      entries: List[AbsolutePath],
  )

  def importMaven(
      projectDir: AbsolutePath,
      outputBase: Option[Path],
      repositoryName: String,
  ): Seq[MbtDependencyModule] = {
    val mavenInstallPaths =
      findAllMavenInstallJson(projectDir)

    if (mavenInstallPaths.isEmpty) {
      scribe.error(
        "No maven_install.json found. Make sure rules_jvm_external is configured and pinned. You might need to run `REPIN=1 bazel run @maven//:pin`."
      )
      Seq.empty[MbtDependencyModule]
    } else {
      val allModules = mavenInstallPaths.flatMap { path =>
        scribe.info(s"bazel-mbt: processing maven lock file: $path")
        val content = path.readText
        val json = gson.fromJson(content, classOf[JsonObject])
        extractArtifacts(
          json,
          outputBase.map(AbsolutePath.apply),
          projectDir,
          repositoryName,
        )
      }

      allModules
        .groupBy(_.id)
        .values
        .map { modules =>
          modules.maxBy(m => if (m.sourcesURI.isDefined) 1 else 0)
        }
        .toSeq
        .sortBy(_.id)
    }

  }

  /**
   * Find all maven_install.json files in various possible locations.
   * Also searches for patterns like maven_install_*.json, *_maven_install.json,
   * and parses MODULE.bazel/WORKSPACE files to find custom lock_file paths.
   */
  private def findAllMavenInstallJson(
      projectDir: AbsolutePath
  ): Set[AbsolutePath] = {
    val candidates = Seq(
      // Standard location
      projectDir.resolve("maven_install.json"),
      // Bzlmod repository rule output (common pattern)
      projectDir.resolve("third_party/maven_install.json"),
      // Some projects put it in a subdirectory
      projectDir.resolve("dependencies/maven_install.json"),
      // Compatibility subdirectory
      projectDir.resolve("compatibility/maven_install.json"),
    )

    // Search for maven_install*.json or *maven*install*.json patterns in project root
    val patternMatches = Try {
      projectDir.list.filter { path =>
        val name = path.filename.toLowerCase
        name.endsWith(".json") && name.contains("maven") && name.contains(
          "install"
        )
      }.toSeq
    }.getOrElse(Seq.empty)

    // Find lock_file paths from MODULE.bazel or WORKSPACE files
    val lockFilePaths = findLockFileFromBazelConfig(projectDir)

    val allCandidates =
      (candidates ++ patternMatches ++ lockFilePaths).distinct
        .filter(_.exists)

    scribe.info(
      s"Found maven_install.json files: ${allCandidates.mkString(", ")}"
    )

    allCandidates.toSet.filter(_.exists)
  }

  /**
   * Parse MODULE.bazel and WORKSPACE files to find lock_file parameter
   * in maven.install or maven_install calls. Also searches through
   * included files if not found in the main config files.
   *
   * Examples of patterns matched:
   * - maven.install(lock_file = "//:maven_install.json")
   * - maven.install(lock_file = "//third_party:maven_install.json")
   * - maven_install(lock_file = "@//:custom_maven.json")
   */
  private def findLockFileFromBazelConfig(
      projectDir: AbsolutePath
  ): Seq[AbsolutePath] = {
    val configFiles = possibleConfigFiles(projectDir)
    val loadedFile = TrieMap.empty[AbsolutePath, String]
    // First, try to find in main config files
    val fromMainConfigs = configFiles.flatMap { configFile =>
      Try {
        val content = configFile.readText
        loadedFile.put(configFile, content)
        extractLockFilePaths(content, projectDir)
      }.getOrElse(Seq.empty)
    }

    if (fromMainConfigs.nonEmpty) fromMainConfigs
    // If not found, search through includes
    else {
      configFiles.flatMap { configFile =>
        Try {
          val content = loadedFile.getOrElse(configFile, configFile.readText)
          val includedFiles = extractIncludePaths(content, projectDir)
          includedFiles.flatMap { includedFile =>
            Try {
              val includedContent = includedFile.readText
              extractLockFilePaths(includedContent, projectDir)
            }.getOrElse(Seq.empty)
          }
        }.getOrElse(Seq.empty)
      }
    }
  }

  /**
   * Tries to extract the repository name from the Bazel config files.
   * If maven.install is not found in the main config files, it will also
   * search through included files.
   *
   * @return the repository name, or "maven" if not found
   */
  def extractRepositoryNameFromBazelConfig(
      projectDir: AbsolutePath
  ): String = {
    val namePattern = """name\s*=\s*"([^"]+)"""".r
    val configFiles = possibleConfigFiles(projectDir)

    def extractFromContent(content: String): Option[String] = {
      val indexOfMavenInstall = content.indexOf("maven_install") match {
        case -1 => content.indexOf("maven.install")
        case index => index
      }
      if (indexOfMavenInstall == -1) None
      else
        namePattern
          .findAllMatchIn(content.substring(indexOfMavenInstall))
          .map(_.group(1))
          .headOption
    }

    val loadedFile = TrieMap.empty[AbsolutePath, String]
    // First, try to find in main config files
    val fromMainConfigs = configFiles.flatMap { configFile =>
      val content = configFile.readText
      loadedFile.put(configFile, content)
      extractFromContent(content)
    }.headOption

    fromMainConfigs.getOrElse {
      // If not found, search through includes
      val fromIncludes = configFiles.flatMap { configFile =>
        val content = loadedFile.getOrElse(configFile, configFile.readText)
        val includedFiles = extractIncludePaths(content, projectDir)
        includedFiles.flatMap { includedFile =>
          Try(includedFile.readText).toOption.flatMap(extractFromContent)
        }
      }.headOption

      fromIncludes.getOrElse("maven")
    }
  }

  /**
   * Extract include paths from Bazel configuration content.
   * Handles patterns like:
   * - include("//path:file.bazel")
   * - include("//:file.bazel")
   * - include("file.bazel")
   */
  private def extractIncludePaths(
      content: String,
      projectDir: AbsolutePath,
  ): Seq[AbsolutePath] = {
    val includePattern = """include\s*\(\s*"([^"]+)"\s*\)""".r

    includePattern
      .findAllMatchIn(content)
      .flatMap { m =>
        val includePath = m.group(1)
        bazelLabelToPath(includePath, projectDir)
      }
      .filter(_.exists)
      .toSeq
  }

  private def possibleConfigFiles(
      projectDir: AbsolutePath
  ): Seq[AbsolutePath] = {
    Seq(
      projectDir.resolve("MODULE.bazel"),
      projectDir.resolve("WORKSPACE"),
      projectDir.resolve("WORKSPACE.bazel"),
    ).filter(_.exists)
  }

  /**
   * Extract lock_file paths from Bazel configuration content.
   * Handles various formats:
   * - lock_file = "//:maven_install.json"
   * - lock_file = "//third_party:maven_install.json"
   * - lock_file = "@//:maven_install.json"
   * - lock_file="//:file.json" (no spaces)
   */
  private def extractLockFilePaths(
      content: String,
      projectDir: AbsolutePath,
  ): Seq[AbsolutePath] = {
    // Pattern to match lock_file parameter in maven.install or maven_install calls
    // Matches: lock_file = "..." or lock_file="..."
    val lockFilePattern =
      """lock_file\s*=\s*"([^"]+)"""".r

    lockFilePattern
      .findAllMatchIn(content)
      .flatMap { m =>
        val lockFilePath = m.group(1)
        bazelLabelToPath(lockFilePath, projectDir)
      }
      .toSeq
  }

  /**
   * Convert a Bazel label to a filesystem path.
   *
   * Handles formats:
   * - "//:maven_install.json" -> projectDir/maven_install.json
   * - "//third_party:maven_install.json" -> projectDir/third_party/maven_install.json
   * - "@//:maven_install.json" -> projectDir/maven_install.json (strip @)
   * - "maven_install.json" -> projectDir/maven_install.json (simple filename)
   */
  private def bazelLabelToPath(
      label: String,
      projectDir: AbsolutePath,
  ): Option[AbsolutePath] = {
    Try {
      val cleanLabel = label.stripPrefix("@")

      if (cleanLabel.startsWith("//")) {
        // Bazel label format: //package:target
        val withoutPrefix = cleanLabel.stripPrefix("//")
        val (packagePath, target) =
          if (withoutPrefix.contains(":")) {
            val parts = withoutPrefix.split(":", 2)
            (parts(0), parts(1))
          } else {
            ("", withoutPrefix)
          }

        if (packagePath.isEmpty)
          Some(projectDir.resolve(target))
        else
          Some(projectDir.resolve(packagePath).resolve(target))
      } else if (cleanLabel.contains(":")) {
        // Simple package:target format without //
        val parts = cleanLabel.split(":", 2)
        Some(projectDir.resolve(parts(0)).resolve(parts(1)))
      } else {
        // Simple filename
        Some(projectDir.resolve(cleanLabel))
      }
    }.toOption.flatten
  }

  /**
   * Extract artifacts from maven_install.json.
   *
   * Supports two formats:
   *
   * New format (rules_jvm_external v5+):
   * {
   *   "artifacts": {
   *     "com.google.guava:guava": {
   *       "version": "31.1-jre",
   *       "shasums": { "jar": "...", "sources": "..." }
   *     }
   *   }
   * }
   *
   * Legacy format (rules_jvm_external v4 and earlier):
   * {
   *   "dependency_tree": {
   *     "dependencies": [
   *       { "coord": "com.google.guava:guava:31.1-jre", "file": "v1/https/..." }
   *     ]
   *   }
   * }
   */
  private def extractArtifacts(
      json: JsonObject,
      outputBase: Option[AbsolutePath],
      projectDir: AbsolutePath,
      repositoryName: String,
  ): Seq[MbtDependencyModule] = {
    scribe.debug(s"Using repository name: $repositoryName")

    // Pre-scan external directories once so per-artifact lookups avoid repeated Files.list() calls.
    val externalDirOpt = outputBase.map(_.resolve("external"))
    val bazelProjectExtDirOpt = Try {
      val bazelLink = projectDir.resolve(s"bazel-${projectDir.filename}")
      if (bazelLink.exists) {
        val d = bazelLink.resolve("external")
        if (d.exists) Some(d) else None
      } else None
    }.getOrElse(None)

    val extDirs: Seq[ScannedExtDir] =
      Seq(externalDirOpt, bazelProjectExtDirOpt).flatten
        .flatMap(d => Try(ScannedExtDir(d, d.list.toList)).toOption)

    // Try new format first (artifacts key at root)
    val artifactsObj = Option(json.getAsJsonObject("artifacts"))
    if (artifactsObj.isDefined) {
      extractFromNewFormat(artifactsObj.get, repositoryName, extDirs)
    } else {
      // Try legacy format (dependency_tree.dependencies array)
      val dependencyTree = Option(json.getAsJsonObject("dependency_tree"))
      dependencyTree.flatMap { dt =>
        Option(dt.getAsJsonArray("dependencies"))
      } match {
        case Some(deps) =>
          scribe.debug("Using legacy dependency_tree format")
          extractFromLegacyFormat(deps, repositoryName, extDirs)
        case None =>
          scribe.debug(
            "No 'artifacts' or 'dependency_tree.dependencies' found in maven_install.json"
          )
          Seq.empty
      }
    }
  }

  /**
   * Extract from new format: { "artifacts": { "group:artifact": { "version": "..." } } }
   */
  private def extractFromNewFormat(
      artifacts: JsonObject,
      repositoryName: String,
      extDirs: Seq[ScannedExtDir],
  ): Seq[MbtDependencyModule] = {
    artifacts.entrySet().asScala.toSeq.flatMap { entry =>
      val coordKey = entry.getKey // e.g., "com.google.guava:guava"
      val artifactInfo = entry.getValue
      if (!artifactInfo.isJsonObject) None
      else
        parseArtifact(
          coordKey,
          artifactInfo,
          repositoryName,
          extDirs,
        )
    }
  }

  /**
   * Extract from legacy format: { "dependency_tree": { "dependencies": [...] } }
   */
  private def extractFromLegacyFormat(
      dependencies: com.google.gson.JsonArray,
      repositoryName: String,
      extDirs: Seq[ScannedExtDir],
  ): Seq[MbtDependencyModule] = {
    val deps = dependencies.iterator().asScala.toSeq

    // Group by base coordinate to pair JARs with sources
    val jarDeps = deps.filter { elem =>
      if (!elem.isJsonObject) false
      else {
        val coord = Option(elem.getAsJsonObject.get("coord"))
          .map(_.getAsString)
          .getOrElse("")
        !coord.contains(":jar:sources:") && !coord.contains("-sources.")
      }
    }

    jarDeps.flatMap { elem =>
      parseLegacyDependency(elem, deps, repositoryName, extDirs)
    }
  }

  private def parseLegacyDependency(
      elem: JsonElement,
      allDeps: Seq[JsonElement],
      repositoryName: String,
      extDirs: Seq[ScannedExtDir],
  ): Option[MbtDependencyModule] = {
    Try {
      val obj = elem.getAsJsonObject
      val coord =
        obj.get("coord").getAsString // e.g., "com.google.guava:guava:31.1-jre"
      val file = Option(obj.get("file")).map(_.getAsString)
      val url = Option(obj.get("url")).map(_.getAsString)

      // Parse coordinate: group:artifact:version
      val parts = coord.split(":")
      if (parts.length < 3) None
      else {
        val (groupId, artifactId, version) = (parts(0), parts(1), parts(2))

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
              findJarFromLegacyPath(f, repositoryName, extDirs)
            }
            .orElse {
              findJarPath(groupId, artifactId, version, repositoryName, extDirs)
            }
            .orElse {
              scribe.warn(s"Downloading from URL, could not find locally: $url")
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
                findJarFromLegacyPath(f, repositoryName, extDirs)
              }
              .orElse {
                findSourcesPath(
                  groupId,
                  artifactId,
                  version,
                  repositoryName,
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
        Some(targetPath.toString)
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
            Some(targetPath.toString)
          } finally {
            in.close()
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
      repositoryName: String,
      extDirs: Seq[ScannedExtDir],
  ): Option[String] = {
    val patterns = Seq(
      s"$repositoryName/$filePath",
      s"maven/$filePath",
      filePath,
    )

    extDirs.view
      .flatMap(findInExternalRepositories(_, patterns, repositoryName))
      .headOption
      .map(_.toString)
  }

  private def parseArtifact(
      coordKey: String,
      artifactInfo: JsonElement,
      repositoryName: String,
      extDirs: Seq[ScannedExtDir],
  ): Option[MbtDependencyModule] = {
    val info = artifactInfo.getAsJsonObject

    // Get version
    val version = Option(info.get("version"))
      .filter(_.isJsonPrimitive)
      .map(_.getAsString)

    version.flatMap { v =>
      if (v.contains("SNAPSHOT")) {
        scribe.debug(s"Skipping SNAPSHOT: $coordKey:$v")
        None
      } else {
        val parts = coordKey.split(":")
        if (parts.length != 2) None
        else {
          val (groupId, artifactId) = (parts(0), parts(1))
          val id = s"$groupId:$artifactId:$v"

          // Find JAR file path
          val jarPath =
            findJarPath(groupId, artifactId, v, repositoryName, extDirs)

          jarPath.map { jar =>
            val sourcesPath =
              findSourcesPath(groupId, artifactId, v, repositoryName, extDirs)

            MbtDependencyModule(
              id = id,
              jar = jar,
              sources = sourcesPath.orNull,
            )
          }
        }
      }
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
  private def findJarPath(
      groupId: String,
      artifactId: String,
      version: String,
      repositoryName: String,
      extDirs: Seq[ScannedExtDir],
  ): Option[String] = {
    val groupPath = groupId.replace('.', '/')
    val jarName = s"$artifactId-$version.jar"

    val bzlmodArtifactDir = toBzlmodArtifactDir(groupId, artifactId, version)

    val workspacePatterns = Seq(
      s"$repositoryName/v1/https/repo1.maven.org/maven2/$groupPath/$artifactId/$version/$jarName",
      s"maven/v1/https/repo1.maven.org/maven2/$groupPath/$artifactId/$version/$jarName",
      s"maven/$groupPath/$artifactId/$version/$jarName",
    ).distinct

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
              repositoryName,
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
   * Find JARs inside Bzlmod maven repository roots such as:
   * rules_jvm_external++maven+unpinned_maven/v1/https/...
   */
  private def findInExternalRepositories(
      scanned: ScannedExtDir,
      relativePaths: Seq[String],
      repositoryName: String,
  ): Option[AbsolutePath] = {
    val directMatch =
      relativePaths.map(path => scanned.dir.resolve(path)).find(_.exists)

    directMatch.orElse {
      val bzlmodRepositoryNames =
        Seq(repositoryName, s"unpinned_$repositoryName").distinct

      val candidateRepositories = scanned.entries.filter { entry =>
        val name = entry.filename.toString
        entry.isDirectory &&
        name.startsWith("rules_jvm_external") &&
        bzlmodRepositoryNames.exists { repo =>
          name.endsWith(s"+$repo") || name.endsWith(s"~$repo")
        }
      }

      val repositoryRelativePaths = relativePaths.flatMap { path =>
        bzlmodRepositoryNames.flatMap { repo =>
          Seq(
            path,
            path.stripPrefix(s"$repo/"),
            path.stripPrefix(s"$repositoryName/"),
          )
        }
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
   * Find the sources JAR file.
   */
  private def findSourcesPath(
      groupId: String,
      artifactId: String,
      version: String,
      repositoryName: String,
      extDirs: Seq[ScannedExtDir],
  ): Option[String] = {
    val groupPath = groupId.replace('.', '/')
    val sourcesJarName = s"$artifactId-$version-sources.jar"

    val bzlmodSourcesDirs = toBzlmodSourcesDirs(groupId, artifactId, version)

    val workspacePatterns = Seq(
      s"$repositoryName/v1/https/repo1.maven.org/maven2/$groupPath/$artifactId/$version/$sourcesJarName",
      s"maven/v1/https/repo1.maven.org/maven2/$groupPath/$artifactId/$version/$sourcesJarName",
    ).distinct

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
              repositoryName,
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
}
