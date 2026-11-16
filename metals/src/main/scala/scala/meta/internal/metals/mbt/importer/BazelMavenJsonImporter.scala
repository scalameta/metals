package scala.meta.internal.metals.mbt.importer

import scala.collection.concurrent.TrieMap
import scala.util.Try

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.mbt.MbtDependencyModule
import scala.meta.internal.metals.mbt.importer.bazel.MavenLockFileParser
import scala.meta.internal.semver.SemVer
import scala.meta.io.AbsolutePath

import com.google.gson.Gson
import com.google.gson.JsonArray
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
  case class ScannedExtDir(
      dir: AbsolutePath,
      entries: List[AbsolutePath],
  ) {

    /**
     * Entries from Bzlmod module extensions (`<prefix>+<apparent>` or
     * `<prefix>~<apparent>`), indexed by their trailing apparent repo name.
     */
    lazy val bzlmodEntriesBySuffix: Map[String, List[AbsolutePath]] =
      entries
        .flatMap { entry =>
          val name = entry.filename
          val sep = math.max(name.lastIndexOf('+'), name.lastIndexOf('~'))
          if (sep >= 0 && sep + 1 < name.length)
            Some(name.substring(sep + 1) -> entry)
          else None
        }
        .groupMap(_._1)(_._2)
  }

  /**
   * Resolve the project's Maven dependency modules from the materialized
   * rules_jvm_external hubs.
   *
   * Each discovered hub's `imported_maven_install.json` is the authoritative
   * lock Bazel actually materialized and built against, so it is the primary
   * module source – more reliable than scanning the source tree
   * for a checked-in `maven_install.json`: a project that pins but does not check
   * the lock into the tree (or stores it somewhere unexpected) still resolves.
   * The heuristic [[findAllMavenInstallJson]] kept as a fallback
   * for when no hub is materialized.
   */
  def importMaven(
      projectDir: AbsolutePath,
      outputBase: Option[AbsolutePath],
      hubs: List[MavenHub],
  ): Seq[MbtDependencyModule] = {
    val repositoryNames =
      if (hubs.isEmpty) Seq(DefaultRepositoryName.value)
      else hubs.map(_.name.value).distinct

    val lockFiles = lockFilesFor(projectDir, hubs)

    if (lockFiles.isEmpty) {
      scribe.error(
        "No maven_install.json found. Make sure rules_jvm_external is configured and pinned. You might need to run `REPIN=1 bazel run @maven//:pin`."
      )
      Seq.empty[MbtDependencyModule]
    } else {
      // Pre-scan external directories once so per-artifact lookups avoid repeated Files.list() calls.
      val scannedExtDirs: Seq[ScannedExtDir] =
        externalDirs(projectDir, outputBase)
          .flatMap(d => Try(ScannedExtDir(d, d.list.toList)).toOption)

      val allModules = lockFiles.flatMap { path =>
        scribe.info(s"bazel-mbt: processing maven lock file: $path")
        val extensionPrefix =
          if (hubs.exists(_.importedLock == path))
            bzlmodExtensionPrefix(path.parent.filename)
          else None
        Try {
          val content = path.readText
          val json = gson.fromJson(content, classOf[JsonObject])
          extractArtifacts(
            json,
            scannedExtDirs,
            repositoryNames,
            path,
            extensionPrefix,
          )
        }.fold(
          e => {
            scribe.error(
              s"bazel-mbt: failed to parse maven lock file $path; skipping it: ${e.getMessage}"
            )
            Seq.empty[MbtDependencyModule]
          },
          identity,
        )
      }
      dedupModulesById(allModules)
    }
  }

  private def lockFilesFor(
      projectDir: AbsolutePath,
      hubs: List[MavenHub],
  ): Seq[AbsolutePath] = {
    val hubLocks = hubs.map(_.importedLock).filter(_.exists)
    if (hubLocks.nonEmpty) hubLocks
    else findAllMavenInstallJson(projectDir).toSeq
  }

  private def dedupModulesById(
      modules: Seq[MbtDependencyModule]
  ): Seq[MbtDependencyModule] =
    modules
      .groupBy(_.id)
      .values
      .map(_.maxBy(_.sourcesURI.isDefined))
      .toSeq
      .sortBy(_.id)

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

  private val DefaultRepositoryName = HubName("maven")

  /**
   * rules_jvm_external's own internal dependency hub. It is a pinned
   * `maven_install` hub and therefore carries the same generated marker as a
   * project hub, but it holds the ruleset's dependencies — not the project's —
   * so it must never be reported as the project's Maven repository.
   */
  private val internalHubNames = Set(
    HubName("rules_jvm_external_deps"),
    HubName("contrib_rules_jvm_deps"),
    HubName("rules_jvm_external"),
  )

  /**
   * A rules_jvm_external hub's apparent repository name, already canonicalized
   * (trailing `+`/`~` segment, `unpinned_` prefix stripped) via
   * [[HubName.fromDirOrLabel]].
   */
  final case class HubName(value: String) extends AnyVal

  object HubName {
    def fromDirOrLabel(raw: String): HubName =
      HubName(apparentRepoName(raw).stripPrefix("unpinned_"))
  }

  case class MavenHub(name: HubName, importedLock: AbsolutePath)

  /**
   * Discover the materialized rules_jvm_external Maven hubs from the `external/`
   * directories, each with its repository name and the path to its
   * `imported_maven_install.json`.
   *
   * A pinned hub is identified by the marker rules_jvm_external writes into
   * every hub it generates: an `imported_maven_install.json` file. The repo
   * name is the directory name itself in WORKSPACE mode (`maven`,
   * `custom_repo`), or the trailing `+`/`~`-separated segment of a bzlmod
   * canonical name (`rules_jvm_external++maven+maven` /
   * `rules_jvm_external~6.2~maven~maven` -> `maven`). A leading `unpinned_` is
   * stripped, and the ruleset's internal [[internalHubNames]] hub is excluded.
   */
  def discoverMavenHubs(extDirs: Seq[AbsolutePath]): List[MavenHub] = {
    val hubs = for {
      extDir <- extDirs if extDir.exists
      entry <- Try(extDir.list.toList).getOrElse(Nil) if isPinnedMavenHub(entry)
      apparent = HubName.fromDirOrLabel(entry.filename)
      if !internalHubNames.contains(apparent)
    } yield MavenHub(apparent, entry.resolve("imported_maven_install.json"))
    hubs.distinctBy(_.name).toList
  }

  private def sanitizeCoordinate(group: String, artifact: String): String = {
    def sanitize(s: String): String = s.replace('.', '_').replace('-', '_')
    s"${sanitize(group)}_${sanitize(artifact)}"
  }

  /**
   * Parse a hub's `imported_maven_install.json` into a
   * `sanitizedCoordinate -> version` map. Handles both lock formats, mirroring
   * [[extractArtifacts]]:
   *   - new (v5+): `{"artifacts": {"group:artifact": {"version": ...}}}`
   *   - legacy (v4 and earlier): `{"dependency_tree": {"dependencies":
   *     [{"coord": "group:artifact:version"}]}}`
   * Tolerates a missing/empty/unknown shape by returning the empty map.
   */
  private def hubCoordinateVersions(hub: MavenHub): Map[String, String] = {
    val parsed = Try {
      val json = gson.fromJson(hub.importedLock.readText, classOf[JsonObject])
      MavenLockFormat.of(json) match {
        case MavenLockFormat.NewFormat(artifacts) =>
          coordinateVersionsFromNewFormat(artifacts)
        case MavenLockFormat.LegacyFormat(dependencies) =>
          coordinateVersionsFromLegacyFormat(dependencies)
        case MavenLockFormat.Unknown => Nil
      }
    }.getOrElse(Nil)
    parsed.toMap
  }

  private sealed trait MavenLockFormat
  private object MavenLockFormat {

    /** New (v5+): `{"artifacts": {"group:artifact": {"version": ...}}}`. */
    case class NewFormat(artifacts: JsonObject) extends MavenLockFormat

    /** Legacy (v4 and earlier): `{"dependency_tree": {"dependencies": [...]}}`. */
    case class LegacyFormat(dependencies: JsonArray) extends MavenLockFormat

    case object Unknown extends MavenLockFormat

    def of(json: JsonObject): MavenLockFormat =
      Option(json.getAsJsonObject("artifacts")) match {
        case Some(artifacts) => NewFormat(artifacts)
        case None =>
          Option(json.getAsJsonObject("dependency_tree"))
            .flatMap(dt => Option(dt.getAsJsonArray("dependencies"))) match {
            case Some(dependencies) => LegacyFormat(dependencies)
            case None => Unknown
          }
      }
  }

  private def coordinateVersionsFromNewFormat(
      artifacts: JsonObject
  ): List[(String, String)] =
    for {
      entry <- artifacts.entrySet().asScala.toList
      parts = entry.getKey.split(":") if parts.length == 2
      value = entry.getValue if value.isJsonObject
      versionElem <- Option(value.getAsJsonObject.get("version")).toList
      if versionElem.isJsonPrimitive
    } yield sanitizeCoordinate(parts(0), parts(1)) -> versionElem.getAsString

  /**
   * `dependency_tree.dependencies` array (legacy format) -> sanitized
   * `coordinate -> version`. Coords are
   * `group:artifact[:packaging[:classifier]]:version`;
   * sources entries (`group:artifact:jar:sources:version`) are skipped.
   */
  private def coordinateVersionsFromLegacyFormat(
      dependencies: JsonArray
  ): List[(String, String)] =
    for {
      elem <- dependencies.iterator().asScala.toList if elem.isJsonObject
      coord <- Option(elem.getAsJsonObject.get("coord"))
        .filter(_.isJsonPrimitive)
        .map(_.getAsString)
        .toList
      if !coord.contains(":jar:sources:")
      parts = coord.split(":") if parts.length >= 3
    } yield sanitizeCoordinate(parts(0), parts(1)) -> parts.last

  /** The coordinate suffix of a dep label: the part after `//:`. */
  private def labelCoordinate(label: String): Option[String] =
    label.split("//:", 2).toList match {
      case _ :: "" :: _ => None
      case _ :: coord :: _ => Some(coord)
      case _ => None
    }

  /**
   * The apparent hub name of a dep label: the repo part before `//`, with any
   * leading `@`/`@@` stripped and reduced to its trailing `+`/`~` segment.
   * Example: `@@rules_jvm_external++maven+maven//:org_scalameta_trees_2_13` ->
   * `maven`. [[None]] when there is no repo part.
   */
  private def labelHubName(label: String): Option[HubName] = {
    val stripped = label.stripPrefix("@@").stripPrefix("@")
    val sep = stripped.indexOf("//")
    val repoPart = if (sep < 0) stripped else stripped.substring(0, sep)
    if (repoPart.isEmpty) None
    else Some(HubName.fromDirOrLabel(repoPart))
  }

  private val versionOrdering: Ordering[String] =
    Ordering.by(v => (Try(SemVer.Version.fromString(v)).toOption, v))

  /**
   * Match Bazel external dep labels to resolved Maven module ids by coordinate
   * suffix, using the hub when one coordinate maps to several module versions.
   *
   * @param externalDeps target -> external dep labels (`@<hub>//:<coord>`)
   * @param modules      resolved Maven modules
   * @param hubs         materialized hubs, providing per-hub `coordinate -> version`
   * @return target -> matched module ids (distinct, order-preserving)
   */
  def matchExternalDeps(
      externalDeps: Map[String, List[String]],
      modules: Seq[MbtDependencyModule],
      hubs: List[MavenHub],
  ): Map[String, List[String]] = {
    val modulesBySuffix: Map[String, List[MbtDependencyModule]] =
      modules.toList.groupBy(m => sanitizeCoordinate(m.organization, m.name))
    val hubVersions: Map[HubName, Map[String, String]] =
      hubs.map(hub => hub.name -> hubCoordinateVersions(hub)).toMap

    def pick(label: String, coord: String): Option[String] = {
      val candidates = modulesBySuffix.getOrElse(coord, Nil)
      if (candidates.sizeIs <= 1) candidates.headOption.map(_.id)
      else {
        val wanted = labelHubName(label)
          .flatMap(hubVersions.get)
          .flatMap(_.get(coord))
        val chosen = wanted
          .flatMap(v => candidates.find(_.version == v))
          .orElse {
            val fallback = candidates.maxBy(_.version)(versionOrdering)
            scribe.warn(
              s"""|bazel-mbt: ambiguous external dep '$label' (coordinate '$coord')
                  |matched ${candidates.size} module versions
                  |[${candidates.map(_.version).mkString(", ")}];
                  |no per-hub version match, picking '${fallback.version}'""".stripMargin
            )
            Some(fallback)
          }
        chosen.map(_.id)
      }
    }

    externalDeps.map { case (target, labels) =>
      val matched = for {
        label <- labels
        coord <- labelCoordinate(label).toList
        id <- pick(label, coord).toList
      } yield id
      target -> matched.distinct
    }
  }

  private def isPinnedMavenHub(dir: AbsolutePath): Boolean =
    dir.isDirectory && dir.resolve("imported_maven_install.json").exists

  private def apparentRepoName(dirName: String): String =
    dirName.split("[+~]").lastOption.getOrElse(dirName)

  def externalDirs(
      projectDir: AbsolutePath,
      outputBase: Option[AbsolutePath],
  ): Seq[AbsolutePath] = {
    val externalDirOpt = outputBase.map(_.resolve("external"))
    val bazelProjectExtDirOpt = Try {
      val bazelLink = projectDir.resolve(s"bazel-${projectDir.filename}")
      if (bazelLink.exists) {
        val d = bazelLink.resolve("external")
        if (d.exists) Some(d) else None
      } else None
    }.getOrElse(None)
    Seq(externalDirOpt, bazelProjectExtDirOpt).flatten.filter(_.exists)
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
   * Bzlmod canonical extension prefix of a repository directory name (including
   * the trailing `+` or `~`), e.g.:
   *   - `+maven_wrapper+maven` -> `Some("+maven_wrapper+")`
   *   - `rules_jvm_external++maven+maven` -> `Some("rules_jvm_external++maven+")`
   *   - `_main~maven_wrapper~maven` -> `Some("_main~maven_wrapper~")`
   *   - `maven` (WORKSPACE mode) -> `None`
   */
  def bzlmodExtensionPrefix(repoDirName: String): Option[String] = {
    val sep =
      math.max(repoDirName.lastIndexOf('+'), repoDirName.lastIndexOf('~'))
    if (sep >= 0) Some(repoDirName.substring(0, sep + 1)) else None
  }

  private val hubBuildFileRepoPattern = """@@?([A-Za-z0-9.+~_-]+)//file\b""".r

  private case class LockedHubArtifact(
      group: String,
      artifact: String,
      version: String,
      hasSources: Boolean,
      isLegacy: Boolean,
  ) {
    def sanitizedCoord: String = sanitizeCoordinate(group, artifact)
    def bzlmodJarRepo: String =
      MavenLockFileParser.toBzlmodArtifactDir(group, artifact, version)
  }

  /**
   * Canonical Bzlmod per-artifact repository labels (`@@<extPrefix><repo>`)
   * needed by `externalDepLabels` whose directory in `external/` is missing or
   * a dangling symlink (e.g. after Bazel 8+ repo-contents-cache GC).
   */
  def missingArtifactRepos(
      externalDepLabels: Set[String],
      hubs: List[MavenHub],
  ): Set[String] =
    if (externalDepLabels.isEmpty || hubs.isEmpty) Set.empty
    else {
      val coordsByHub: Map[HubName, Set[String]] =
        (for {
          label <- externalDepLabels
          hubName <- labelHubName(label)
          coord <- labelCoordinate(label)
        } yield hubName -> coord).groupMap(_._1)(_._2)

      (for {
        hub <- hubs
        directCoords = coordsByHub.getOrElse(hub.name, Set.empty)
        if directCoords.nonEmpty && hub.importedLock.exists
        hubDir = hub.importedLock.parent
        extPrefix <- bzlmodExtensionPrefix(hubDir.filename).toList
        repo <- missingArtifactReposForHub(
          hub,
          hubDir,
          extPrefix,
          directCoords,
        )
      } yield repo).toSet
    }

  private def missingArtifactReposForHub(
      hub: MavenHub,
      hubDir: AbsolutePath,
      extPrefix: String,
      directCoords: Set[String],
  ): Set[String] =
    Try {
      val extDir = hubDir.parent
      val json = gson.fromJson(hub.importedLock.readText, classOf[JsonObject])
      val (artifacts, depsByCoord) = lockedArtifactsAndDeps(json)
      val artifactsByCoord = artifacts.groupBy(_.sanitizedCoord)
      val coordByVersionedAlias =
        artifacts.map(a => a.bzlmodJarRepo -> a.sanitizedCoord).toMap

      val seedCoords =
        directCoords.map(c => coordByVersionedAlias.getOrElse(c, c))

      @scala.annotation.tailrec
      def closeCoords(
          frontier: Set[String],
          visited: Set[String],
      ): Set[String] =
        if (frontier.isEmpty) visited
        else {
          val next =
            frontier.flatMap(c => depsByCoord.getOrElse(c, Set.empty)) --
              visited
          closeCoords(next, visited ++ next)
        }

      val reachableCoords = closeCoords(seedCoords, seedCoords)
      val definedReposOpt = definedArtifactReposInHubBuild(hubDir)

      for {
        coord <- reachableCoords
        artifact <- artifactsByCoord.getOrElse(coord, Nil)
        apparentRepo <- candidateArtifactRepos(artifact, definedReposOpt)
        canonicalRepo = s"$extPrefix$apparentRepo"
        if !extDir.resolve(canonicalRepo).exists
      } yield s"@@$canonicalRepo"
    }.getOrElse(Set.empty)

  private def candidateArtifactRepos(
      artifact: LockedHubArtifact,
      definedReposOpt: Option[Set[String]],
  ): Seq[String] = {
    val jarRepo = artifact.bzlmodJarRepo
    definedReposOpt match {
      case Some(defined) =>
        val srcCandidates = MavenLockFileParser.toBzlmodSourcesDirs(
          artifact.group,
          artifact.artifact,
          artifact.version,
        )
        (jarRepo +: srcCandidates).filter(defined.contains)
      case None =>
        val srcCandidates =
          if (!artifact.hasSources) Seq.empty
          else if (artifact.isLegacy)
            Seq(
              MavenLockFileParser.toBzlmodJarSourcesDir(
                artifact.group,
                artifact.artifact,
                artifact.version,
              )
            )
          else
            Seq(
              MavenLockFileParser.toBzlmodSourcesDir(
                artifact.group,
                artifact.artifact,
                artifact.version,
              )
            )
        jarRepo +: srcCandidates
    }
  }

  private def definedArtifactReposInHubBuild(
      hubDir: AbsolutePath
  ): Option[Set[String]] = {
    val buildFileOpt =
      Seq(hubDir.resolve("BUILD"), hubDir.resolve("BUILD.bazel")).find(_.exists)
    for {
      buildFile <- buildFileOpt
      content <- Try(buildFile.readText).toOption
      repos = (for {
        m <- hubBuildFileRepoPattern.findAllMatchIn(content)
      } yield apparentRepoName(m.group(1))).toSet
      if repos.nonEmpty
    } yield repos
  }

  private def lockedArtifactsAndDeps(
      json: JsonObject
  ): (List[LockedHubArtifact], Map[String, Set[String]]) =
    MavenLockFormat.of(json) match {
      case MavenLockFormat.NewFormat(artifactsObj) =>
        val artifacts =
          for {
            entry <- artifactsObj.entrySet().asScala.toList
            parts = entry.getKey.split(":") if parts.length == 2
            if entry.getValue.isJsonObject
            info = entry.getValue.getAsJsonObject
            version <- Option(info.get("version"))
              .filter(_.isJsonPrimitive)
              .map(_.getAsString)
            if !version.contains("SNAPSHOT")
            hasSources = Option(info.getAsJsonObject("shasums"))
              .exists(_.has("sources"))
          } yield LockedHubArtifact(
            parts(0),
            parts(1),
            version,
            hasSources,
            isLegacy = false,
          )

        val depsByCoord =
          Option(json.getAsJsonObject("dependencies")) match {
            case Some(depsObj) =>
              (for {
                entry <- depsObj.entrySet().asScala
                parts = entry.getKey.split(":") if parts.length == 2
                if entry.getValue.isJsonArray
                depCoords = (for {
                  depElem <- entry.getValue.getAsJsonArray.asScala
                  if depElem.isJsonPrimitive
                  depParts = depElem.getAsString.split(":")
                  if depParts.length >= 2
                } yield sanitizeCoordinate(depParts(0), depParts(1))).toSet
              } yield sanitizeCoordinate(parts(0), parts(1)) -> depCoords).toMap
            case None => Map.empty[String, Set[String]]
          }

        (artifacts, depsByCoord)

      case MavenLockFormat.LegacyFormat(dependencies) =>
        val elems = dependencies.asScala.toList.filter(_.isJsonObject)
        val sourcesCoords = (for {
          elem <- elems
          coord <- Option(elem.getAsJsonObject.get("coord"))
            .filter(_.isJsonPrimitive)
            .map(_.getAsString)
          if coord.contains(":jar:sources:")
        } yield coord).toSet

        val artifacts =
          for {
            elem <- elems
            obj = elem.getAsJsonObject
            coord <- Option(obj.get("coord"))
              .filter(_.isJsonPrimitive)
              .map(_.getAsString)
            if !coord.contains(":jar:sources:") && !coord.contains("-sources.")
            parts = coord.split(":") if parts.length >= 3
            version = parts.last if !version.contains("SNAPSHOT")
            hasSources = sourcesCoords.contains(
              s"${parts(0)}:${parts(1)}:jar:sources:$version"
            )
          } yield LockedHubArtifact(
            parts(0),
            parts(1),
            version,
            hasSources,
            isLegacy = true,
          )

        val depsByCoord = (for {
          elem <- elems
          obj = elem.getAsJsonObject
          coord <- Option(obj.get("coord"))
            .filter(_.isJsonPrimitive)
            .map(_.getAsString)
          if !coord.contains(":jar:sources:") && !coord.contains("-sources.")
          parts = coord.split(":") if parts.length >= 3
          depsArr <- Option(obj.getAsJsonArray("dependencies"))
          depCoords = (for {
            depElem <- depsArr.asScala if depElem.isJsonPrimitive
            depParts = depElem.getAsString.split(":") if depParts.length >= 2
          } yield sanitizeCoordinate(depParts(0), depParts(1))).toSet
        } yield sanitizeCoordinate(parts(0), parts(1)) -> depCoords).toMap

        (artifacts, depsByCoord)

      case MavenLockFormat.Unknown =>
        (Nil, Map.empty)
    }

  /**
   * Extract artifacts from maven_install.json.
   */
  private def extractArtifacts(
      json: JsonObject,
      extDirs: Seq[ScannedExtDir],
      repositoryNames: Seq[String],
      lockFile: AbsolutePath,
      extensionPrefix: Option[String],
  ): Seq[MbtDependencyModule] = {
    scribe.debug(s"Using repository names: ${repositoryNames.mkString(", ")}")

    MavenLockFileParser.create(json) match {
      case Some(parser) =>
        parser.parse(repositoryNames, extDirs, extensionPrefix)
      case None =>
        scribe.debug(
          s"No 'artifacts' or 'dependency_tree.dependencies' found in $lockFile"
        )
        Seq.empty
    }
  }

}
