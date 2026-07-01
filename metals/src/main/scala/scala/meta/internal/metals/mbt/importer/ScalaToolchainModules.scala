package scala.meta.internal.metals.mbt.importer

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Try
import scala.util.Using
import scala.util.control.NonFatal

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.mbt.MbtDependencyModule

import coursier.Fetch
import coursier.Repositories
import coursier.core.Classifier
import coursier.core.Dependency
import coursier.core.Module
import coursier.core.ModuleName
import coursier.core.Organization

/**
 * Scala toolchain jars for Bazel MBT imports, resolved per Scala version.
 *
 * Bazel provides the Scala standard library (and, for targets that depend on
 * the rules_scala `scala_compile_classpath` toolchain target, the compiler
 * jars) through toolchain resolution, which is invisible to the
 * `@maven//`-based dependency matching: those jars never show up in
 * `maven_install.json`, so MBT namespaces end up without `scala-library` on
 * the presentation compiler classpath and without sources jars for
 * external-dependency navigation. This resolves the equivalent artifacts
 * (with sources) from Maven Central per Scala version present in the build.
 */
object ScalaToolchainModules {

  /**
   * The resolved toolchain modules and which module ids apply to a namespace
   * of a given Scala version. `libraryIdsByVersion` is the standard library
   * (every Scala namespace needs it); `compilerIdsByVersion` is the full
   * compiler classpath (only namespaces whose targets depend on a
   * `scala_compile_classpath` toolchain target need it);
   * `compilerClasspathTargets` are those targets.
   */
  case class Resolution(
      modules: Seq[MbtDependencyModule],
      libraryIdsByVersion: Map[String, List[String]],
      compilerIdsByVersion: Map[String, List[String]],
      compilerClasspathTargets: Set[String],
      testingIdsByVersion: Map[String, List[String]] = Map.empty,
      testTargets: Set[String] = Set.empty,
  ) {

    /**
     * Toolchain module ids for one namespace, minus those whose
     * `organization:name` is already provided by `existingIds` (e.g. a
     * `scala-library` pinned through `@maven//`), self-deduplicated (the
     * compiler bundle transitively contains the library).
     */
    def moduleIdsFor(
        scalaVersion: Option[String],
        targets: Iterable[String],
        existingIds: Set[String],
    ): Set[String] =
      scalaVersion match {
        case None => Set.empty
        case Some(version) =>
          val needsCompiler = targets.exists(compilerClasspathTargets)
          val needsTesting = targets.exists(testTargets)
          val candidates =
            libraryIdsByVersion.getOrElse(version, Nil) ++
              (if (needsCompiler)
                 compilerIdsByVersion.getOrElse(version, Nil)
               else Nil) ++
              (if (needsTesting) testingIdsByVersion.getOrElse(version, Nil)
               else Nil)
          val existingNames = existingIds.map(organizationName)
          candidates
            .distinctBy(organizationName)
            .filterNot(id => existingNames(organizationName(id)))
            .toSet
      }

    private def organizationName(id: String): String =
      id.split(":", 3).take(2).mkString(":")
  }

  object Resolution {
    val empty: Resolution =
      Resolution(Nil, Map.empty, Map.empty, Set.empty)
  }

  /**
   * A direct dependency on the rules_scala toolchain target providing the
   * full compiler classpath — the marker that a target compiles against the
   * Scala compiler itself (compiler plugins and the like).
   */
  def isCompilerClasspathLabel(label: String): Boolean =
    label.endsWith(":scala_compile_classpath")

  private val trailingScalaVersion = """_(\d+(?:_\d+)+)$""".r
  private val mavenStyleJarName = """(.+?)-(\d[^-]*)\.jar""".r

  // rules_scala's `scala_config` repository rule writes the resolved default
  // version into `config.bzl` as a machine-generated `SCALA_VERSION='X.Y.Z'`
  // line (anchored at the start of the line so it never matches the sibling
  // `SCALA_VERSIONS=[...]` / `SCALA_MAJOR_VERSION=...` entries). Matching the
  // generated value — not the build-file source — is what makes this robust to
  // however the workspace expressed the version (a constant, a variable, a
  // string concatenation, a `--repo_env` default).
  private val scalaConfigVersionPattern =
    """(?m)^\s*SCALA_VERSION\s*=\s*['"]([^'"]+)['"]""".r

  /**
   * The default Scala version rules_scala resolved for the workspace, read from
   * the generated `@…rules_scala_config` repository's `config.bzl`. rules_scala
   * materializes this repository during the loading phase that `bazel query`
   * triggers, so it is available without building. The repository name ends in
   * `rules_scala_config` under both bzlmod (`…+scala_config+rules_scala_config`)
   * and WORKSPACE (`io_bazel_rules_scala_config`) naming. `None` when rules_scala
   * is not in use (the standard-library coordinate then carries the version).
   *
   * More than one matching repository can exist in a single output base — a
   * stale directory left after a rules_scala upgrade (Bazel does not GC the old
   * `external/<…rules_scala_config>` when the canonical name embeds the module
   * version) or a WORKSPACE→bzlmod migration — each carrying a different default
   * version. Take the highest, matching how every other Scala-version fallback
   * resolves (`BazelMbtBuildSupport.maxVersion`), so a lingering older repo
   * cannot shadow the current toolchain's version.
   */
  def scalaConfigVersion(externalDir: Path): Option[String] =
    BazelMbtBuildSupport.maxVersion(
      listDirectory(externalDir)
        .filter(dir =>
          dir.getFileName.toString.endsWith("rules_scala_config") &&
            Files.isDirectory(dir)
        )
        .flatMap { dir =>
          val configBzl = dir.resolve("config.bzl")
          if (Files.isRegularFile(configBzl))
            Try(new String(Files.readAllBytes(configBzl))).toOption
              .flatMap(scalaConfigVersionPattern.findFirstMatchIn)
              .map(_.group(1))
          else None
        }
    )

  /**
   * Testing-framework jars per Scala version, discovered in the Bazel output
   * base. `scala_test` targets compile against scalatest/scalactic through
   * the rules_scala testing toolchain — invisible to `@maven//` matching like
   * the compiler jars, but rules_scala materializes them (WITH `-src.jar`
   * sources) under `external/<…scalatest…_x_y_z>/`, so the exact artifacts
   * Bazel compiles against are available offline, no version guessing. Repos
   * not using rules_scala's naming simply yield nothing.
   */
  def testingModules(
      externalDir: Path
  ): Map[String, List[MbtDependencyModule]] = {
    val modules = for {
      dir <- listDirectory(externalDir)
      if Files.isDirectory(dir)
      name = dir.getFileName.toString
      if name.contains("scalatest") || name.contains("scalactic")
      version <- trailingScalaVersion
        .findFirstMatchIn(name)
        .map(_.group(1).replace('_', '.'))
      jar <- listDirectory(dir).find(p =>
        p.getFileName.toString.endsWith(".jar") &&
          !p.getFileName.toString.endsWith("-src.jar")
      )
      nameMatch <- mavenStyleJarName.findFirstMatchIn(jar.getFileName.toString)
    } yield {
      val artifact = nameMatch.group(1)
      val artifactVersion = nameMatch.group(2)
      val organization =
        if (artifact.startsWith("scalactic")) "org.scalactic"
        else "org.scalatest"
      val sources = jar.resolveSibling(
        jar.getFileName.toString.stripSuffix(".jar") + "-src.jar"
      )
      version -> MbtDependencyModule(
        id = s"$organization:$artifact:$artifactVersion",
        jar = jar.toUri.toString,
        sources =
          if (Files.isRegularFile(sources)) sources.toUri.toString
          else null,
      )
    }
    modules.groupBy { case (version, _) => version }.map {
      case (version, pairs) =>
        version -> pairs
          .map { case (_, module) => module }
          .sortBy(_.id)
          .distinctBy(_.id)
    }
  }

  /**
   * Scala versions for which a rules_scala testing-toolchain repository
   * (scalatest/scalactic) directory exists under `externalDir`, regardless of
   * whether its jar has been materialized. Lets callers tell "this workspace
   * uses the testing toolchain but Bazel hasn't fetched the jars yet" (worth
   * warning about) apart from "this workspace doesn't use it at all" (expected
   * to yield nothing).
   */
  def testingToolchainVersions(externalDir: Path): Set[String] =
    (for {
      dir <- listDirectory(externalDir)
      if Files.isDirectory(dir)
      name = dir.getFileName.toString
      if name.contains("scalatest") || name.contains("scalactic")
      version <- trailingScalaVersion
        .findFirstMatchIn(name)
        .map(_.group(1).replace('_', '.'))
    } yield version).toSet

  /**
   * Entries of `dir`, or an empty list if it is not a directory or cannot be
   * listed. The directory stream is always closed (the previous iterator-based
   * scan leaked a file handle per call), and transient IO errors — e.g. a
   * repository being materialized concurrently — are logged rather than
   * propagated, so toolchain discovery never fails the whole import.
   */
  private def listDirectory(dir: Path): List[Path] =
    if (Files.isDirectory(dir))
      Using(Files.list(dir))(_.iterator().asScala.toList).fold(
        error => {
          scribe.warn(s"bazel-mbt: could not list $dir: ${error.getMessage}")
          Nil
        },
        identity,
      )
    else Nil

  def resolve(
      libraryVersions: Set[String],
      compilerVersions: Set[String],
      compilerClasspathTargets: Set[String],
      testingModulesByVersion: Map[String, List[MbtDependencyModule]] =
        Map.empty,
      testTargets: Set[String] = Set.empty,
  )(implicit ec: ExecutionContext): Future[Resolution] = {
    val libraries = libraryVersions.toSeq.sorted.map { version =>
      fetchBundle(libraryDependency(version)).map(version -> _)
    }
    val compilers = compilerVersions.toSeq.sorted.map { version =>
      fetchBundle(compilerDependency(version)).map(version -> _)
    }
    for {
      libraryBundles <- Future.sequence(libraries)
      compilerBundles <- Future.sequence(compilers)
    } yield {
      val modules =
        (libraryBundles ++ compilerBundles)
          .flatMap { case (_, bundle) => bundle }
          .concat(testingModulesByVersion.values.flatten)
          .distinctBy(_.id)
      Resolution(
        modules,
        libraryBundles.toMap.map { case (version, bundle) =>
          version -> bundle.map(_.id)
        },
        compilerBundles.toMap.map { case (version, bundle) =>
          version -> bundle.map(_.id)
        },
        compilerClasspathTargets,
        testingModulesByVersion.map { case (version, bundle) =>
          version -> bundle.map(_.id)
        },
        testTargets,
      )
    }
  }

  private def libraryDependency(scalaVersion: String): Dependency =
    if (scalaVersion.startsWith("3"))
      scalaLangDependency("scala3-library_3", scalaVersion)
    else scalaLangDependency("scala-library", scalaVersion)

  private def compilerDependency(scalaVersion: String): Dependency =
    if (scalaVersion.startsWith("3"))
      scalaLangDependency("scala3-compiler_3", scalaVersion)
    else scalaLangDependency("scala-compiler", scalaVersion)

  private def scalaLangDependency(
      name: String,
      version: String,
  ): Dependency =
    Dependency(
      Module(Organization("org.scala-lang"), ModuleName(name), Map.empty),
      version,
    )

  /**
   * The dependency and its transitive closure as modules with jar + sources
   * URIs. Resolution failures (offline, nonexistent version) only log: the
   * import is strictly better with toolchain modules but must not fail
   * without them.
   */
  private def fetchBundle(
      dependency: Dependency
  )(implicit ec: ExecutionContext): Future[List[MbtDependencyModule]] =
    Fetch()
      .addRepositories(Repositories.central)
      .withDependencies(Seq(dependency))
      .withMainArtifacts(true)
      .addClassifiers(Classifier.sources)
      .futureResult()
      .withTimeout(3, TimeUnit.MINUTES, Some("fetching Scala toolchain jars"))
      .map { result =>
        val byModule = result.fullDetailedArtifacts
          .collect { case (dep, publication, _, Some(file)) =>
            (dep.module, dep.version, publication.classifier, file)
          }
          .groupBy { case (module, version, _, _) => (module, version) }
        byModule.toList
          .sortBy { case ((module, version), _) =>
            (module.repr, version)
          }
          .map { case ((module, version), artifacts) =>
            def fileFor(classifier: Classifier) =
              artifacts.collectFirst {
                case (_, _, c, file) if c == classifier =>
                  file.toURI().toString()
              }
            MbtDependencyModule(
              id =
                s"${module.organization.value}:${module.name.value}:$version",
              jar = fileFor(Classifier.empty).orNull,
              sources = fileFor(Classifier.sources).orNull,
            )
          }
      }
      .recover { case NonFatal(error) =>
        scribe.warn(
          s"bazel-mbt: could not resolve Scala toolchain jars for " +
            s"${dependency.module.repr}:${dependency.version}: $error"
        )
        Nil
      }

}
