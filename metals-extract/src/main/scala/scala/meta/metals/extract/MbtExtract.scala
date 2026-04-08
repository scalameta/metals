package scala.meta.metals.extract

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import scala.util.Try

/**
 * CLI entry point for metals-extract.
 *
 * Extracts dependency information from Maven, Gradle, or Bazel projects
 * and writes it to .metals/mbt.json for use with Metals.
 *
 * Usage:
 *   metals-extract [options] [project-dir]
 *
 * Options:
 *   --maven       Force Maven extraction
 *   --gradle      Force Gradle extraction
 *   --bazel       Force Bazel extraction
 *   --output      Output file (default: .metals/mbt.json)
 *   --sources     Also resolve source jars (slower)
 *   --help        Show help message
 */
object MbtExtract {

  case class Config(
      projectDir: Path = Paths.get(".").toAbsolutePath.normalize,
      outputFile: Option[Path] = None,
      forceBuildTool: Option[BuildTool] = None,
      resolveSources: Boolean = true,
      verbose: Boolean = false,
      scalaVersion: Option[String] = None // e.g. "2.12", "2.13", "3"
  )

  sealed trait BuildTool
  object BuildTool {
    case object Maven extends BuildTool
    case object Gradle extends BuildTool
    case object Bazel extends BuildTool
  }

  def main(args: Array[String]): Unit = {
    parseArgs(args.toList) match {
      case Left(error) =>
        System.err.println(s"Error: $error")
        System.err.println("Use --help for usage information")
        System.exit(1)
      case Right(None) =>
        // Help was printed
        System.exit(0)
      case Right(Some(config)) =>
        run(config) match {
          case Left(error) =>
            System.err.println(s"Error: $error")
            System.exit(1)
          case Right(count) =>
            println(s"Successfully extracted $count dependencies to ${outputPath(config)}")
        }
    }
  }

  def run(config: Config): Either[String, Int] = {
    val projectDir = config.projectDir

    if (!Files.isDirectory(projectDir)) {
      return Left(s"Project directory does not exist: $projectDir")
    }

    val buildTool = config.forceBuildTool.getOrElse(detectBuildTool(projectDir))

    val result: Either[String, Seq[DependencyModule]] = buildTool match {
      case BuildTool.Maven =>
        println("Extracting dependencies from Maven project...")
        MavenExtractor.extract(projectDir, config.resolveSources, config.verbose)
      case BuildTool.Gradle =>
        println("Extracting dependencies from Gradle project...")
        GradleExtractor.extract(projectDir, config.resolveSources, config.verbose)
      case BuildTool.Bazel =>
        println("Extracting dependencies from Bazel project...")
        BazelExtractor.extract(projectDir, config.verbose)
    }

    result.flatMap { modules =>
      val filtered = filterByScalaVersion(modules, config.scalaVersion)
      writeOutput(config, filtered)
    }
  }

  /**
   * Filter dependencies by Scala version suffix.
   * If scalaVersion is None, returns all modules.
   * If scalaVersion is Some("2.13"), filters out artifacts ending with _2.12 or _3
   */
  private def filterByScalaVersion(
      modules: Seq[DependencyModule],
      scalaVersion: Option[String]
  ): Seq[DependencyModule] = {
    scalaVersion match {
      case None => modules
      case Some(version) =>
        // Suffixes to exclude based on target version
        val excludeSuffixes = version match {
          case v if v.startsWith("2.12") => Seq("_2.13", "_3")
          case v if v.startsWith("2.13") => Seq("_2.12", "_3")
          case v if v.startsWith("3") => Seq("_2.12", "_2.13")
          case _ => Seq.empty
        }

        modules.filter { m =>
          val artifactId = m.id.split(":").lift(1).getOrElse("")
          // Check if this artifact has a Scala version suffix
          val hasScalaSuffix = artifactId.matches(".*_\\d+(\\.\\d+)?$") ||
            artifactId.matches(".*_3$")

          if (!hasScalaSuffix) {
            // No Scala suffix, keep it (Java library)
            true
          } else {
            // Has Scala suffix, check if it matches target or should be excluded
            !excludeSuffixes.exists(suffix => artifactId.endsWith(suffix))
          }
        }
    }
  }

  private def detectBuildTool(projectDir: Path): BuildTool = {
    val hasMaven = Files.exists(projectDir.resolve("pom.xml"))
    val hasGradle = Files.exists(projectDir.resolve("build.gradle")) ||
      Files.exists(projectDir.resolve("build.gradle.kts"))
    val hasBazel = Files.exists(projectDir.resolve("WORKSPACE")) ||
      Files.exists(projectDir.resolve("WORKSPACE.bazel")) ||
      Files.exists(projectDir.resolve("MODULE.bazel"))

    (hasMaven, hasGradle, hasBazel) match {
      case (true, false, false) => BuildTool.Maven
      case (false, true, false) => BuildTool.Gradle
      case (false, false, true) => BuildTool.Bazel
      case (true, true, _) =>
        System.err.println("Warning: Both Maven and Gradle detected, using Maven")
        BuildTool.Maven
      case (_, _, true) if hasMaven || hasGradle =>
        System.err.println("Warning: Bazel detected alongside Maven/Gradle, using Bazel")
        BuildTool.Bazel
      case _ =>
        System.err.println("Warning: No build tool detected, trying Maven")
        BuildTool.Maven
    }
  }

  private def outputPath(config: Config): Path = {
    config.outputFile.getOrElse(config.projectDir.resolve(".metals/mbt.json"))
  }

  private def writeOutput(config: Config, modules: Seq[DependencyModule]): Either[String, Int] = {
    val output = outputPath(config)
    Try {
      Files.createDirectories(output.getParent)
      val mbtBuild = MbtBuild(modules)
      val json = mbtBuild.toJson
      Files.writeString(output, json)
      modules.size
    }.toEither.left.map(e => s"Failed to write output: ${e.getMessage}")
  }

  private def parseArgs(args: List[String]): Either[String, Option[Config]] = {
    def loop(args: List[String], config: Config): Either[String, Option[Config]] = {
      args match {
        case Nil => Right(Some(config))
        case "--help" :: _ =>
          printHelp()
          Right(None)
        case "--maven" :: rest =>
          loop(rest, config.copy(forceBuildTool = Some(BuildTool.Maven)))
        case "--gradle" :: rest =>
          loop(rest, config.copy(forceBuildTool = Some(BuildTool.Gradle)))
        case "--bazel" :: rest =>
          loop(rest, config.copy(forceBuildTool = Some(BuildTool.Bazel)))
        case "--output" :: path :: rest =>
          loop(rest, config.copy(outputFile = Some(Paths.get(path))))
        case "--cwd" :: path :: rest =>
          val projectPath = Paths.get(path).toAbsolutePath.normalize
          loop(rest, config.copy(projectDir = projectPath))
        case "-C" :: path :: rest =>
          val projectPath = Paths.get(path).toAbsolutePath.normalize
          loop(rest, config.copy(projectDir = projectPath))
        case "--sources" :: rest =>
          loop(rest, config.copy(resolveSources = true))
        case "--no-sources" :: rest =>
          loop(rest, config.copy(resolveSources = false))
        case "--verbose" :: rest =>
          loop(rest, config.copy(verbose = true))
        case "-v" :: rest =>
          loop(rest, config.copy(verbose = true))
        case "--scala-version" :: version :: rest =>
          loop(rest, config.copy(scalaVersion = Some(version)))
        case path :: rest if !path.startsWith("-") =>
          val projectPath = Paths.get(path).toAbsolutePath.normalize
          loop(rest, config.copy(projectDir = projectPath))
        case unknown :: _ =>
          Left(s"Unknown option: $unknown")
      }
    }
    loop(args, Config())
  }

  private def printHelp(): Unit = {
    println("""metals-extract - Extract dependency information for Metals
              |
              |Usage: metals-extract [options] [project-dir]
              |
              |Options:
              |  --cwd, -C <path>       Set project directory (alternative to positional arg)
              |  --maven                Force Maven extraction (even if Gradle/Bazel files exist)
              |  --gradle               Force Gradle extraction (even if Maven/Bazel files exist)
              |  --bazel                Force Bazel extraction (even if Maven/Gradle files exist)
              |  --output <path>        Output file (default: <project>/.metals/mbt.json)
              |  --sources              Resolve source jars (default: enabled)
              |  --no-sources           Skip resolving source jars (faster)
              |  --scala-version <ver>  Filter by Scala version (2.12, 2.13, or 3)
              |  --verbose, -v          Enable verbose output
              |  --help                 Show this help message
              |
              |Auto-detection:
              |  - pom.xml present                       -> Maven
              |  - build.gradle(.kts) present            -> Gradle
              |  - WORKSPACE(.bazel) or MODULE.bazel     -> Bazel
              |
              |Examples:
              |  metals-extract                          # Extract from current directory
              |  metals-extract /path/to/project         # Extract from specified directory
              |  metals-extract --scala-version 2.13     # Filter to Scala 2.13 artifacts only
              |  metals-extract --bazel --scala-version 3  # Bazel project, Scala 3 only
              |  metals-extract --no-sources             # Skip source jar resolution
              |""".stripMargin)
  }
}

/**
 * Represents a single dependency module with its coordinates and file paths.
 */
case class DependencyModule(
    id: String, // e.g. "com.google.guava:guava:30.0-jre"
    jar: String, // absolute path to jar
    sources: Option[String] = None // absolute path to sources jar
)

/**
 * The mbt.json format that Metals reads.
 */
case class MbtBuild(dependencyModules: Seq[DependencyModule]) {
  def toJson: String = {
    val modulesJson = dependencyModules.map { m =>
      val sourcesField = m.sources match {
        case Some(s) => s""", "sources": "${escapeJson(s)}""""
        case None => ""
      }
      s"""    { "id": "${escapeJson(m.id)}", "jar": "${escapeJson(m.jar)}"$sourcesField }"""
    }
    s"""{
       |  "dependencyModules": [
       |${modulesJson.mkString(",\n")}
       |  ]
       |}""".stripMargin
  }

  private def escapeJson(s: String): String = {
    s.replace("\\", "\\\\").replace("\"", "\\\"")
  }
}
