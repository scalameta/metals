package scala.meta.internal.metals.mbt.importer

import java.nio.file.Files
import java.nio.file.Path

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.builds.BazelBuildTool
import scala.meta.internal.builds.BazelDigest
import scala.meta.internal.builds.BazelProjectViewTargets
import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.Tables
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.mbt.MbtBuild
import scala.meta.internal.metals.mbt.MbtDependencyModule
import scala.meta.internal.process.ExitCodes
import scala.meta.io.AbsolutePath

/**
 * Extracts [[MbtBuild]] from a Bazel workspace using `bazel query`. Target
 * scope comes from the Bazel project view (`targets:` in `.bazelproject` or
 * `*.bazelproject`); namespaces are grouped using the user-selected mode.
 */
abstract class BazelMbtImporter(
    val projectRoot: AbsolutePath,
    shellRunner: ShellRunner,
    userConfig: () => UserConfiguration,
    languageClient: Option[MetalsLanguageClient] = None,
    tables: Option[Tables] = None,
)(implicit ec: ExecutionContext)
    extends MbtImportProvider {

  override val name: String = "bazel"

  private lazy val queryEnv =
    BazelQuery.Env(projectRoot, shellRunner, userConfig().javaHome)

  override def extract(workspace: AbsolutePath): Future[Unit] =
    selectedNamespaceMode().flatMap(extract(workspace, _))

  private def extract(
      workspace: AbsolutePath,
      namespaceMode: BazelMbtNamespaceMode,
  ): Future[Unit] = {
    val out = outputPath(workspace)
    Files.createDirectories(out.toNIO.getParent)
    val patterns = BazelProjectViewTargets.patterns(projectRoot)
    for {
      outputBase <- queryOutputBase()
      bazelBin <- queryBazelBin()
      repositoryName = BazelMavenJsonImporter
        .extractRepositoryNameFromBazelConfig(projectRoot)
      _ = scribe.info(s"bazel-mbt: found repository name: $repositoryName")
      mavenImportStart = System.nanoTime()
      dependencyModules = BazelMavenJsonImporter
        .importMaven(projectRoot, outputBase, repositoryName)
      _ = scribe.debug(
        s"bazel-mbt: importMaven took ${(System.nanoTime() - mavenImportStart) / 1_000_000}ms"
      )
      ruleKindsQueryOutput <- BazelQuery
        .buildRuleKindsQuery(patterns)
        .run(queryEnv)
      targets = asLines(ruleKindsQueryOutput)
      _ = scribe.info(s"bazel-mbt: found ${targets.size} targets")
      fullInfoQueryOutput <- BazelQuery
        .fullInformationQuery(targets)
        .run(queryEnv)
      protoDump = new BazelTargetsProtoDump(fullInfoQueryOutput)
      // The rule-kinds query (cheap `label` output) found targets but the
      // full-information query produced no rule data — the import would write a
      // structurally valid but empty `mbt.json`. Surface it instead: the usual
      // cause is a Bazel too old for `--output=streamed_jsonproto` with
      // `--proto:output_rule_attrs`/`--proto:flatten_selects`, or a query that
      // otherwise failed (see the bazel query warnings above).
      _ =
        if (targets.nonEmpty && protoDump.isEmpty)
          scribe.error(
            s"bazel-mbt: the rule-kinds query matched ${targets.size} targets " +
              "but the full-information query returned no rule data; the MBT " +
              "import will be empty. Check that the Bazel version supports " +
              "`bazel query --output=streamed_jsonproto " +
              "--proto:output_rule_attrs=...`, and see the bazel query " +
              "warnings above."
          )
      srcs = protoDump.getLabels("srcs")
      scalacOptions = protoDump.getStrings("scalacopts")
      javacOptions = protoDump.getStrings("javacopts")
      runTargets = targets
        .filter(target =>
          protoDump.ruleClassesByTarget
            .get(target)
            .exists(isRunnableRule)
        )
        .toSet
      classDirectories = classDirectoriesForRunTargets(
        bazelBin,
        runTargets,
        protoDump.ruleOutputsByTarget,
      )
      deps = queryDeps(targets.toSet, targets, protoDump)
      externalDeps = protoDump.externalDepsByTarget(targets)
      externalDepModules = BazelMbtImporter.matchExternalDepsToModules(
        externalDeps,
        dependencyModules,
      )
      scalaVersions = protoDump.getStrings("scala_version")
      effectiveScalaVersionValue =
        // The version rules_scala actually resolved for the workspace, read
        // from its generated config repository — robust to however the version
        // was expressed in source (constant, variable, concatenation), unlike
        // parsing the build-file text.
        scalaVersionFromConfigRepo(outputBase)
          .orElse(BazelMbtBuildSupport.maxVersion(scalaVersions.values.flatten))
          // Repo-agnostic: every Scala target compiles against the standard
          // library, whose coordinate carries the exact version no matter how
          // the workspace names its version constant.
          .orElse(scalaVersionFromModules(dependencyModules))
          // Final explicit escape hatch, works for any repo.
          .orElse(userConfig().fallbackScalaVersion)
      // Source-providing filegroups are inlined into consuming targets'
      // `srcs` (flattened across `select()` branches) by [[getLabels]], losing
      // which branch each copy came from — but the same `deps(set(...))` query
      // also visits the filegroups themselves, so their select()-aware `srcs`
      // recover the cross-version copies (e.g. per-Scala-version copies of a
      // Java class). They resolve with the default configuration like any
      // unpinned target.
      filegroupLabels = protoDump.filegroupLabels.toList.sorted
      scalaVersionByTarget = (targets ++ filegroupLabels).map { target =>
        val targetScalaVersion = scalaVersions
          .get(target)
          .flatMap(BazelMbtBuildSupport.maxVersion)
          .orElse(effectiveScalaVersionValue)
        target -> targetScalaVersion
      }.toMap
      inactiveSources = BazelBuildSrcs.inactiveSources(
        protoDump.srcsByTarget,
        scalaVersionByTarget,
      )
      // Every source that lives in a `select_for_scala_version` branch (active
      // or inactive). Sources outside any branch are version-agnostic; the
      // version-branch namespaces use this to carry their origin's unconditional
      // sources without dragging in the default configuration's copies.
      versionSpecificSourceLabels = protoDump.srcsByTarget.values
        .flatMap(_.byVersion.values.flatten)
        .toSet
      _ = scribe.info(
        s"bazel-mbt: ${inactiveSources.size} version-specific sources " +
          "from inactive select() branches"
      )
      // Bazel supplies the Scala standard library (and compiler, for targets
      // depending on the rules_scala `scala_compile_classpath` toolchain
      // target) through toolchain resolution, invisible to the maven
      // matching above — resolve the equivalent jars per Scala version.
      compilerClasspathTargets = targets.filter { target =>
        protoDump.depsByTarget
          .getOrElse(target, Nil)
          .exists(ScalaToolchainModules.isCompilerClasspathLabel)
      }.toSet
      libraryVersions =
        (scalaVersionByTarget.collect {
          case (target, Some(version))
              if srcs.getOrElse(target, Nil).exists(_.endsWith(".scala")) =>
            version
        } ++ inactiveSources.collect {
          case (label, inactive) if label.endsWith(".scala") =>
            inactive.version
        }).toSet
      compilerVersions = (scalaVersionByTarget.collect {
        case (target, Some(version)) if compilerClasspathTargets(target) =>
          version
      } ++ inactiveSources.values.collect {
        case BazelBuildSrcs.InactiveSource(version, origin)
            if compilerClasspathTargets(origin) =>
          version
      }).toSet
      // scala_test targets additionally compile against scalatest/scalactic
      // from the rules_scala testing toolchain — materialized (with sources)
      // in the output base, so discovered there rather than resolved.
      testTargets = targets.filter { target =>
        protoDump.ruleClassesByTarget.get(target).contains("scala_test")
      }.toSet
      testingVersions = (scalaVersionByTarget.collect {
        case (target, Some(version)) if testTargets(target) => version
      } ++ inactiveSources.values.collect {
        case BazelBuildSrcs.InactiveSource(version, origin)
            if testTargets(origin) =>
          version
      }).toSet
      testingModulesByVersion =
        discoverTestingModules(testTargets, testingVersions, outputBase)
      toolchain <- ScalaToolchainModules.resolve(
        libraryVersions,
        compilerVersions,
        compilerClasspathTargets,
        testingModulesByVersion,
        testTargets,
      )
      _ = scribe.info(
        s"bazel-mbt: resolved ${toolchain.modules.size} Scala toolchain " +
          s"modules for versions ${libraryVersions.toSeq.sorted.mkString(", ")}"
      )
      build = BazelSrcjarSources.materializeAll(
        workspace,
        BazelMbtBuildSupport.fromDiscovery(
          namespaceMode,
          targets,
          srcs,
          scalacOptions,
          javacOptions,
          deps,
          externalDepModules,
          runTargets,
          classDirectories,
          dependencyModules,
          scalaVersionByTarget,
          inactiveSources,
          versionSpecificSourceLabels,
          toolchain,
        ),
      )
      _ <- Future(Files.writeString(out.toNIO, MbtBuild.toJson(build)))
    } yield ()
  }

  private def asLines(output: String) =
    output.linesIterator.map(_.trim).filter(_.nonEmpty).toList

  private def isRunnableRule(ruleClass: String): Boolean =
    ruleClass == "scala_binary" || ruleClass == "java_binary" ||
      ruleClass == "scala_test" || ruleClass == "java_test"

  private def classDirectoriesForRunTargets(
      bazelBin: Option[Path],
      runTargets: Set[String],
      ruleOutputsByTarget: Map[String, List[String]],
  ): Map[String, String] =
    bazelBin.toList.flatMap { bin =>
      for {
        target <- runTargets.toList
        output <- ruleOutputsByTarget
          .getOrElse(target, Nil)
          .find(isClassJarOutput)
        relative <- BazelMbtBuildSupport.fileLabelToWorkspaceRelativePath(
          output
        )
      } yield target -> bin.resolve(relative).toString
    }.toMap

  private def isClassJarOutput(label: String): Boolean =
    label.endsWith(".jar") &&
      !label.endsWith("-src.jar") &&
      !label.endsWith("_deploy.jar") &&
      !label.endsWith("_deploy-src.jar")

  private def selectedNamespaceMode(): Future[BazelMbtNamespaceMode] =
    rememberedNamespaceMode match {
      case Some(mode) => Future.successful(mode)
      case None => requestNamespaceMode()
    }

  private def rememberedNamespaceMode: Option[BazelMbtNamespaceMode] =
    tables.flatMap { tables =>
      tables.bazelMbtNamespaceModes
        .selectedMode(projectRoot)
        .flatMap(BazelMbtNamespaceMode.fromName)
    }

  private def requestNamespaceMode(): Future[BazelMbtNamespaceMode] =
    languageClient match {
      case Some(client) =>
        client
          .showMessageRequest(Messages.BazelMbtNamespaceChoice.params())
          .asScala
          .map { item =>
            val selected = Messages.BazelMbtNamespaceChoice.selectedMode(item)
            selected.foreach(rememberNamespaceMode)
            selected.getOrElse(BazelMbtNamespaceMode.Workspace)
          }
      case None =>
        Future.successful(BazelMbtNamespaceMode.Workspace)
    }

  private def rememberNamespaceMode(mode: BazelMbtNamespaceMode): Unit =
    tables.foreach { tables =>
      tables.bazelMbtNamespaceModes.chooseMode(projectRoot, mode.name)
    }

  override def isBuildRelated(path: AbsolutePath): Boolean =
    BazelBuildTool.isBazelRelatedPath(projectRoot, path)

  override def digest(workspace: AbsolutePath): Option[String] =
    BazelDigest.current(projectRoot)

  private def queryDeps(
      targetSet: Set[String],
      orderedTargets: List[String],
      protoDump: BazelTargetsProtoDump,
  ): Map[String, List[String]] = {
    orderedTargets.map { target =>
      target -> protoDump.depsByTarget.getOrElse(target, Nil).filter(targetSet)
    }.toMap
  }

  /**
   * scalatest/scalactic jars for the `scala_test` targets, discovered in the
   * Bazel output base where rules_scala materializes the testing toolchain (it
   * is invisible to `@maven//` matching like the compiler jars). Rather than
   * silently dropping them, warns when the toolchain is in use but its jars
   * cannot be found: either the output base did not resolve, or the
   * repositories are declared but not materialized (the unconfigured `bazel
   * query` we run does not fetch artifacts). Both leave test sources without
   * scalatest on the presentation-compiler classpath. Workspaces that get
   * scalatest through `@maven//` instead have no toolchain repositories, so
   * they yield nothing here without a spurious warning.
   */
  private def discoverTestingModules(
      testTargets: Set[String],
      testingVersions: Set[String],
      outputBase: Option[Path],
  ): Map[String, List[MbtDependencyModule]] =
    if (testTargets.isEmpty) Map.empty
    else
      outputBase match {
        case None =>
          scribe.warn(
            "bazel-mbt: scala_test targets are present but the Bazel output " +
              "base could not be resolved; scalatest/scalactic will be missing " +
              "from test sources on the presentation-compiler classpath"
          )
          Map.empty
        case Some(base) =>
          val external = base.resolve("external")
          val discovered = ScalaToolchainModules
            .testingModules(external)
            .filter { case (version, _) => testingVersions(version) }
          val declaredButMissing = ScalaToolchainModules
            .testingToolchainVersions(external)
            .intersect(testingVersions)
            .diff(discovered.keySet)
          if (declaredButMissing.nonEmpty)
            scribe.warn(
              "bazel-mbt: rules_scala testing-toolchain repositories for Scala " +
                s"${declaredButMissing.toSeq.sorted.mkString(", ")} are present " +
                s"under $external but their jars are not materialized; build or " +
                "fetch the scala_test targets so navigation into " +
                "scalatest/scalactic works"
            )
          discovered
      }

  /**
   * The Scala version implied by the resolved standard library on the
   * classpath: `org.scala-lang:scala-library:X.Y.Z` (Scala 2) or
   * `org.scala-lang:scala3-library_3:X.Y.Z` (Scala 3). This is repo-agnostic —
   * every Scala target compiles against the stdlib and its coordinate carries
   * the exact version, regardless of how the workspace names its version
   * constant. When several are present (cross-build) the highest is chosen,
   * which only feeds the workspace-wide fallback; per-target `scala_version`
   * still takes precedence in [[scalaVersionByTarget]].
   */
  private def scalaVersionFromModules(
      modules: Seq[MbtDependencyModule]
  ): Option[String] = {
    val versions = modules.collect {
      case module
          if module.id.startsWith("org.scala-lang:scala-library:") ||
            module.id.startsWith("org.scala-lang:scala3-library_3:") =>
        module.id.split(":").last
    }
    BazelMbtBuildSupport.maxVersion(versions)
  }

  /**
   * The workspace's default Scala version as rules_scala resolved it, read from
   * the generated `@…rules_scala_config` repository under the Bazel output base
   * (see [[ScalaToolchainModules.scalaConfigVersion]]). Reading the resolved
   * value rather than parsing `MODULE.bazel`/`WORKSPACE` source means it does
   * not matter whether the version was written as a literal, a variable, or a
   * string concatenation.
   */
  private def scalaVersionFromConfigRepo(
      outputBase: Option[Path]
  ): Option[String] =
    outputBase.flatMap { base =>
      ScalaToolchainModules.scalaConfigVersion(base.resolve("external"))
    }

  private def queryOutputBase(): Future[Option[Path]] = {
    queryBazelInfo("output_base")
  }

  private def queryBazelBin(): Future[Option[Path]] = {
    queryBazelInfo("bazel-bin")
  }

  private def queryBazelInfo(key: String): Future[Option[Path]] = {
    val buf = new StringBuilder()
    shellRunner
      .run(
        "bazel-info",
        List("bazel", "info", key),
        projectRoot,
        redirectErrorOutput = false,
        javaHome = userConfig().javaHome,
        processOut = line => buf.append(line),
        processErr = _ => (),
      )
      .future
      .map {
        case ExitCodes.Success =>
          val output = buf.toString.trim
          if (output.nonEmpty) Some(Path.of(output)) else None
        case _ => None
      }
  }

}

object BazelMbtImporter {

  /**
   * Attaches the imported Maven [[MbtDependencyModule]]s to the targets that
   * (transitively) depend on them.
   *
   * `rules_jvm_external` exposes every resolved coordinate as an alias in the
   * Maven hub repository at `//:<group>_<artifact>` (with `.` and `-` sanitized
   * to `_`). The match keys on that coordinate target suffix and deliberately
   * ignores the dependency label's repository part, because the repository part
   * is not stable:
   *
   *   - The hub's apparent name is whatever the workspace chose in
   *     `use_repo(maven, "...")` and need not equal the `maven_install` `name`
   *     we detect — e.g. `use_repo(maven, mvn = "maven")` makes BUILD files
   *     reference `@mvn//:guava` while the install is still named `maven`.
   *     Reconstructing and matching a `@<detected-name>//:...` label would then
   *     silently match nothing.
   *   - `bazel query` renders the hub apparently (`@maven//...`) or canonically
   *     (`@@rules_jvm_external++maven+maven//...`, or `~~`/`~` on Bazel < 7.1)
   *     depending on whether it is `use_repo`'d into the root module. Comparing
   *     only the coordinate suffix makes both renderings match identically.
   *
   * Labels that are not coordinate aliases (the per-artifact backing repos such
   * as `@@rules_jvm_external++maven+com_google_guava_guava_33_6_0_jre//jar:jar`,
   * toolchain repos, etc.) carry a different target and so never match.
   */
  def matchExternalDepsToModules(
      externalDeps: Map[String, List[String]],
      dependencyModules: Seq[MbtDependencyModule],
  ): Map[String, List[String]] = {
    val moduleIdByCoordinate = dependencyModules.flatMap { module =>
      coordinateLabelSuffix(module.id).map(_ -> module.id)
    }.toMap

    externalDeps.map { case (target, deps) =>
      val matchedModuleIds = (for {
        dep <- deps
        suffix <- labelTargetSuffix(dep)
        moduleId <- moduleIdByCoordinate.get(suffix)
      } yield moduleId).distinct
      target -> matchedModuleIds
    }
  }

  /**
   * The `rules_jvm_external` alias target a Maven module id resolves to:
   * `//:<group>_<artifact>` with `.` and `-` replaced by `_`. `None` for ids
   * that lack at least a `group:artifact` pair.
   */
  private def coordinateLabelSuffix(moduleId: String): Option[String] = {
    val parts = moduleId.split(":")
    if (parts.length >= 2) {
      val sanitizedGroup = parts(0).replace('.', '_').replace('-', '_')
      val sanitizedArtifact = parts(1).replace('.', '_').replace('-', '_')
      Some(s"//:${sanitizedGroup}_$sanitizedArtifact")
    } else None
  }

  /** The package-and-target part of a label (`//...`), dropping the `@repo`. */
  private def labelTargetSuffix(label: String): Option[String] = {
    val idx = label.indexOf("//")
    if (idx < 0) None else Some(label.substring(idx))
  }

}
