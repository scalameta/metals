package scala.meta.internal.metals.mbt.importer

import java.nio.file.Path

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
  )

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
      outputBase: Option[Path],
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
      val allModules = lockFiles.flatMap { path =>
        scribe.info(s"bazel-mbt: processing maven lock file: $path")
        Try {
          val content = path.readText
          val json = gson.fromJson(content, classOf[JsonObject])
          extractArtifacts(
            json,
            outputBase.map(AbsolutePath.apply),
            projectDir,
            repositoryNames,
            path,
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
  private val internalHubNames = Set(HubName("rules_jvm_external_deps"))

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
   * Extract artifacts from maven_install.json.
   */
  private def extractArtifacts(
      json: JsonObject,
      outputBase: Option[AbsolutePath],
      projectDir: AbsolutePath,
      repositoryNames: Seq[String],
      lockFile: AbsolutePath,
  ): Seq[MbtDependencyModule] = {
    scribe.debug(s"Using repository names: ${repositoryNames.mkString(", ")}")

    // Pre-scan external directories once so per-artifact lookups avoid repeated Files.list() calls.
    val extDirs: Seq[ScannedExtDir] =
      externalDirs(projectDir, outputBase)
        .flatMap(d => Try(ScannedExtDir(d, d.list.toList)).toOption)

    MavenLockFileParser.create(json) match {
      case Some(parser) => parser.parse(repositoryNames, extDirs)
      case None =>
        scribe.debug(
          s"No 'artifacts' or 'dependency_tree.dependencies' found in $lockFile"
        )
        Seq.empty
    }
  }

}
