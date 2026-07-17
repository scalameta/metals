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
import scala.meta.internal.process.ProcessOutput
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
  import BazelMbtImporter._

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
      maven = resolveMavenDependencies(outputBase)
      (targets, protoDump) <- queryTargetsAndRules(patterns)
      classification = computeSourceClassification(
        targets,
        protoDump,
        outputBase,
        maven.dependencyModules,
      )
      versions = computeVersionSets(targets, protoDump, classification)
      toolchain <- resolveToolchainAndTesting(versions, outputBase)
      _ <- assembleAndWrite(
        workspace,
        namespaceMode,
        out,
        targets,
        protoDump,
        bazelBin,
        classification,
        maven,
        toolchain,
      )
    } yield ()
  }

  private def resolveMavenDependencies(
      outputBase: Option[Path]
  ): MavenResolution = {
    val mavenHubs = BazelMavenJsonImporter
      .discoverMavenHubs(
        BazelMavenJsonImporter.externalDirs(
          projectRoot,
          outputBase.map(AbsolutePath.apply),
        )
      )
    scribe.info(
      s"bazel-mbt: found maven hubs: ${mavenHubs.map(_.name.value).mkString(", ")}"
    )
    val mavenImportStart = System.nanoTime()
    val dependencyModules =
      BazelMavenJsonImporter.importMaven(projectRoot, outputBase, mavenHubs)
    scribe.debug(
      s"bazel-mbt: importMaven took ${(System.nanoTime() - mavenImportStart) / 1_000_000}ms"
    )
    MavenResolution(mavenHubs, dependencyModules)
  }

  private def queryTargetsAndRules(
      patterns: List[String]
  ): Future[(List[String], BazelTargetsProtoDump)] =
    for {
      ruleKindsQueryOutput <- BazelQuery
        .buildRuleKindsQuery(patterns)
        .run(queryEnv)
      targets = asLines(ruleKindsQueryOutput)
      _ = scribe.info(s"bazel-mbt: found ${targets.size} targets")
      protoDump <- BazelQuery
        .fullInformationQuery(targets)
        .runProtoDump(queryEnv)
      _ <-
        if (targets.nonEmpty && protoDump.isEmpty)
          Future.failed(
            new IllegalStateException(
              s"bazel-mbt: the rule-kinds query matched ${targets.size} targets " +
                "but the full-information query returned no rule data; keeping " +
                "the previous MBT import."
            )
          )
        else Future.unit
    } yield (targets, protoDump)

  private def computeSourceClassification(
      targets: List[String],
      protoDump: BazelTargetsProtoDump,
      outputBase: Option[Path],
      dependencyModules: Seq[MbtDependencyModule],
  ): SourceClassification = {
    val srcs = protoDump.srcLabelsByTarget
    val (genSrcOutputsByTarget, genSrcLabels) =
      if (userConfig().importGeneratedSourcesMbt)
        computeGenSrcOutputs(srcs, protoDump)
      else
        (Map.empty[String, List[String]], Set.empty[String])
    val filteredSrcs = srcs.map { case (t, labels) =>
      t -> labels.filterNot(genSrcLabels)
    }
    val scalaVersions = protoDump.getStrings("scala_version")
    val effectiveScalaVersionValue =
      BazelEffectiveScalaVersionResolver.resolve(
        outputBase,
        dependencyModules,
        scalaVersions,
        userConfig(),
      )

    val filegroupLabels = protoDump.filegroupLabels.toList.sorted
    val scalaVersionByTarget = (targets ++ filegroupLabels).map { target =>
      val targetScalaVersion = scalaVersions
        .get(target)
        .flatMap(BazelScalaVersions.maxVersion)
        .orElse(effectiveScalaVersionValue)
      target -> targetScalaVersion
    }.toMap
    val inactiveSources = BazelBuildSrcs.inactiveSources(
      protoDump.srcsByTarget,
      scalaVersionByTarget,
    )
    // Every source living in a `select_for_scala_version` branch (active or
    // inactive); sources outside any branch are version-agnostic.
    val versionSpecificSourceLabels = protoDump.srcsByTarget.values
      .flatMap(_.byVersion.values.flatten)
      .toSet
    scribe.info(
      s"bazel-mbt: ${inactiveSources.size} version-specific sources " +
        "from inactive select() branches"
    )
    SourceClassification(
      filteredSrcs,
      genSrcOutputsByTarget,
      scalaVersionByTarget,
      inactiveSources,
      versionSpecificSourceLabels,
    )
  }

  private def computeVersionSets(
      targets: List[String],
      protoDump: BazelTargetsProtoDump,
      classification: SourceClassification,
  ): VersionSets = {
    val srcs = protoDump.srcLabelsByTarget
    val scalaVersionByTarget = classification.scalaVersionByTarget
    val inactiveSources = classification.inactiveSources

    // Supplied through toolchain resolution; invisible to the maven matching
    // above (see ScalaToolchainModules).
    val compilerClasspathTargets = targets.filter { target =>
      protoDump.depsByTarget
        .getOrElse(target, Nil)
        .exists(ScalaToolchainModules.isCompilerClasspathLabel)
    }.toSet
    val libraryVersions =
      (scalaVersionByTarget.collect {
        case (target, Some(version))
            if srcs
              .getOrElse(target, Nil)
              .exists(BazelSrcjarSources.isScalaBearingSource) =>
          version
      } ++ inactiveSources.collect {
        case (label, inactive)
            if BazelSrcjarSources.isScalaBearingSource(label) =>
          inactive.version
      }).toSet
    val compilerVersions = scalaVersionsOfTargets(
      scalaVersionByTarget,
      inactiveSources,
      compilerClasspathTargets,
    )
    val testTargets = targets.filter { target =>
      protoDump.ruleClassesByTarget.get(target).contains("scala_test")
    }.toSet
    val testingVersions = scalaVersionsOfTargets(
      scalaVersionByTarget,
      inactiveSources,
      testTargets,
    )
    VersionSets(
      libraryVersions,
      compilerVersions,
      testingVersions,
      compilerClasspathTargets,
      testTargets,
    )
  }

  private def resolveToolchainAndTesting(
      versions: VersionSets,
      outputBase: Option[Path],
  ): Future[ScalaToolchainModules.Resolution] = {
    val testingModulesByVersion = discoverTestingModules(
      versions.testTargets,
      versions.testingVersions,
      outputBase,
    )
    for {
      toolchain <- ScalaToolchainModules.resolve(
        versions.libraryVersions,
        versions.compilerVersions,
        versions.compilerClasspathTargets,
        testingModulesByVersion,
        versions.testTargets,
      )
      _ = scribe.info(
        s"bazel-mbt: resolved ${toolchain.modules.size} Scala toolchain " +
          s"modules for versions ${versions.libraryVersions.toSeq.sorted.mkString(", ")}"
      )
    } yield toolchain
  }

  private def assembleAndWrite(
      workspace: AbsolutePath,
      namespaceMode: BazelMbtNamespaceMode,
      out: AbsolutePath,
      targets: List[String],
      protoDump: BazelTargetsProtoDump,
      bazelBin: Option[Path],
      classification: SourceClassification,
      maven: MavenResolution,
      toolchain: ScalaToolchainModules.Resolution,
  ): Future[Unit] = {
    val scalacOptions = protoDump.getStrings("scalacopts")
    val javacOptions = protoDump.getStrings("javacopts")
    val runTargets = targets
      .filter(target =>
        protoDump.ruleClassesByTarget
          .get(target)
          .exists(isRunnableRule)
      )
      .toSet
    val classDirectories = classDirectoriesForRunTargets(
      bazelBin,
      runTargets,
      protoDump.ruleOutputsByTarget,
    )
    val deps = queryDeps(targets, protoDump)
    val reachableLabelsByTarget = protoDump.reachableLabels(targets)
    val externalDeps =
      protoDump.externalDepsByTarget(reachableLabelsByTarget)
    val externalDepModules = BazelMavenJsonImporter.matchExternalDeps(
      externalDeps,
      maven.dependencyModules,
      maven.hubs,
    )
    val importTargets =
      targets.filter(protoDump.jarLabelsByImportTarget.contains)
    scribe.info(s"bazel-mbt: import targets: ${importTargets.toSet}")
    val importTargetJarLabels = importTargets.map { target =>
      target -> resolvedJarLabels(
        target,
        protoDump.jarLabelsByImportTarget,
        protoDump.ruleOutputsByTarget,
      )
    }.toMap
    val importJarModules = buildImportJarModules(
      importTargetJarLabels,
      protoDump.sourcesJarByImportTarget,
      bazelBin,
    )
    val importDepModules = buildImportModuleIdsByTarget(
      targets,
      importTargetJarLabels,
      reachableLabelsByTarget,
    )
    val allDependencyModules = maven.dependencyModules ++ importJarModules
    val allExternalDepModules = mergeDepsModuleMaps(
      externalDepModules,
      importDepModules,
    )
    val build = BazelSrcjarSources.materializeAll(
      workspace,
      BazelMbtBuildSupport.fromDiscovery(
        namespaceMode,
        targets,
        classification.filteredSrcs,
        scalacOptions,
        javacOptions,
        deps,
        allExternalDepModules,
        runTargets,
        classDirectories,
        allDependencyModules,
        classification.scalaVersionByTarget,
        classification.inactiveSources,
        classification.versionSpecificSourceLabels,
        toolchain,
        classification.genSrcOutputsByTarget,
      ),
    )
    Future(Files.writeString(out.toNIO, MbtBuild.toJson(build)))
  }

  private def buildImportJarModules(
      importTargetJarLabels: Map[String, List[String]],
      sourcesJarByTarget: Map[String, Option[String]],
      bazelBin: Option[Path],
  ): Seq[MbtDependencyModule] = {
    val seen = scala.collection.mutable.Set.empty[String]
    importTargetJarLabels.toSeq.sortBy(_._1).flatMap {
      case (target, jarLabels) =>
        // unlike "jars", "srcjar" argument is not a list — attach it to the first jar only
        val sourcesUri =
          if (jarLabels.nonEmpty)
            sourcesJarByTarget
              .get(target)
              .flatten
              .flatMap(
                resolveJarUri(_, bazelBin)
              )
          else None
        jarLabels.zipWithIndex.flatMap { case (jarLabel, i) =>
          moduleIdFromJarLabel(jarLabel) match {
            case None =>
              scribe.warn(
                s"bazel-mbt: could not derive module id for jar label $jarLabel"
              )
              None
            case Some(moduleId) if seen.contains(moduleId) => None
            case Some(moduleId) =>
              seen += moduleId
              resolveJarUri(jarLabel, bazelBin).map { jar =>
                MbtDependencyModule(
                  id = moduleId,
                  jar = jar,
                  sources = if (i == 0) sourcesUri.orNull else null,
                )
              }
          }
        }
    }
  }

  private def resolveJarUri(
      label: String,
      bazelBin: Option[Path],
  ): Option[String] = {
    BazelLabels.fileLabelToWorkspaceRelativePath(label).flatMap { relative =>
      val candidate = projectRoot.resolve(relative)
      if (Files.exists(candidate.toNIO)) Some(candidate.toURI.toString)
      else {
        val generatedCandidate = bazelBin.map(_.resolve(relative))
        generatedCandidate.filter(Files.exists(_)) match {
          case Some(path) => Some(path.toUri.toString)
          case None =>
            scribe.warn(
              s"bazel-mbt: could not resolve jar for label $label"
            )
            None
        }
      }
    }
  }

  private def asLines(output: String) =
    output.linesIterator.map(_.trim).filter(_.nonEmpty).toList

  private def isRunnableRule(ruleClass: String): Boolean =
    ruleClass == "scala_binary" || ruleClass == "java_binary" ||
      ruleClass == "scala_test" || ruleClass == "java_test"

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

  private def scalaVersionsOfTargets(
      scalaVersionByTarget: Map[String, Option[String]],
      inactiveSources: Map[String, BazelBuildSrcs.InactiveSource],
      isRelevant: String => Boolean,
  ): Set[String] =
    (scalaVersionByTarget.collect {
      case (target, Some(version)) if isRelevant(target) => version
    } ++ inactiveSources.values.collect {
      case BazelBuildSrcs.InactiveSource(version, origin)
          if isRelevant(origin) =>
        version
    }).toSet

  /**
   * scalatest/scalactic jars for the `scala_test` targets, discovered in the
   * Bazel output base where rules_scala materializes the testing toolchain
   * (invisible to `@maven//` matching like the compiler jars).
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

  private def queryOutputBase(): Future[Option[Path]] = {
    queryBazelInfo("output_base")
  }

  private def queryBazelBin(): Future[Option[Path]] = {
    queryBazelInfo("bazel-bin")
  }

  // Retried once: a stale Bazel server can fail this first call with exit 36
  // while restarting.
  private def queryBazelInfo(key: String): Future[Option[Path]] =
    runBazelInfo(key).flatMap {
      case Some(path) => Future.successful(Some(path))
      case None =>
        scribe.info(s"bazel-mbt: retrying failed 'bazel info $key'")
        runBazelInfo(key)
    }

  private def runBazelInfo(key: String): Future[Option[Path]] = {
    val buf = new StringBuilder()
    shellRunner
      .run(
        "bazel-info",
        List("bazel", "info", key),
        projectRoot,
        redirectErrorOutput = false,
        javaHome = userConfig().javaHome,
        processOut = ProcessOutput.Lines(line => buf.append(line)),
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

  private case class MavenResolution(
      hubs: List[BazelMavenJsonImporter.MavenHub],
      dependencyModules: Seq[MbtDependencyModule],
  )

  private case class SourceClassification(
      filteredSrcs: Map[String, List[String]],
      genSrcOutputsByTarget: Map[String, List[String]],
      scalaVersionByTarget: Map[String, Option[String]],
      inactiveSources: Map[String, BazelBuildSrcs.InactiveSource],
      versionSpecificSourceLabels: Set[String],
  )

  private case class VersionSets(
      libraryVersions: Set[String],
      compilerVersions: Set[String],
      testingVersions: Set[String],
      compilerClasspathTargets: Set[String],
      testTargets: Set[String],
  )

  private def isClassJarOutput(label: String): Boolean =
    label.endsWith(".jar") &&
      !label.endsWith("-src.jar") &&
      !label.endsWith("_deploy.jar") &&
      !label.endsWith("_deploy-src.jar")

  // A `jars` label may be a genrule (return its output jar) or a raw file.
  private def resolvedJarLabels(
      target: String,
      jarLabelsByTarget: Map[String, List[String]],
      ruleOutputsByTarget: Map[String, List[String]],
  ): List[String] =
    jarLabelsByTarget.getOrElse(target, Nil).map { label =>
      ruleOutputsByTarget
        .getOrElse(label, Nil)
        .find(isClassJarOutput)
        .getOrElse(label)
    }

  private def moduleIdFromJarLabel(label: String): Option[String] =
    BazelLabels.fileLabelToWorkspaceRelativePath(label).map { relative =>
      val lastSlash = relative.lastIndexOf('/')
      if (lastSlash >= 0) {
        val pkg = relative.substring(0, lastSlash).replace('/', '.')
        val fileName = relative.substring(lastSlash + 1)
        s"$pkg:$fileName:local"
      } else {
        s"root:$relative:local"
      }
    }

  private def buildImportModuleIdsByTarget(
      targets: List[String],
      importTargetJarLabels: Map[String, List[String]],
      reachableLabelsByTarget: Map[String, List[String]],
  ): Map[String, List[String]] = {
    val ownModuleIds = importTargetJarLabels.map { case (target, jarLabels) =>
      target -> jarLabels.flatMap(moduleIdFromJarLabel).distinct
    }
    targets.map { target =>
      val transitiveIds = reachableLabelsByTarget
        .getOrElse(target, Nil)
        .flatMap(ownModuleIds.getOrElse(_, Nil))
      target -> (ownModuleIds.getOrElse(target, Nil) ++ transitiveIds).distinct
    }.toMap
  }

  private def mergeDepsModuleMaps(
      maps: Map[String, List[String]]*
  ): Map[String, List[String]] = {
    val allKeys = maps.flatMap(_.keySet).toSet
    allKeys.map { key =>
      key -> maps.flatMap(_.getOrElse(key, Nil)).distinct.toList
    }.toMap
  }

  private def classDirectoriesForRunTargets(
      bazelBin: Option[Path],
      runTargets: Set[String],
      ruleOutputsByTarget: Map[String, List[String]],
  ): Map[String, String] =
    bazelBin.toList.flatMap { bin =>
      for {
        target <- runTargets
        output <- ruleOutputsByTarget
          .getOrElse(target, Nil)
          .find(isClassJarOutput)
        relative <- BazelLabels.fileLabelToWorkspaceRelativePath(output)
      } yield target -> bin.resolve(relative).toString
    }.toMap

  private def queryDeps(
      targets: List[String],
      protoDump: BazelTargetsProtoDump,
  ): Map[String, List[String]] = {
    val targetSet = targets.toSet
    targets.map { target =>
      target -> protoDump.depsByTarget.getOrElse(target, Nil).filter(targetSet)
    }.toMap
  }

  // Resolves generated-output `srcs` labels to their `bazel-bin`-relative paths
  // (as `uncheckedSources`) and returns those labels so callers drop them from
  // `sources`.
  private def computeGenSrcOutputs(
      srcsByTarget: Map[String, List[String]],
      protoDump: BazelTargetsProtoDump,
  ): (Map[String, List[String]], Set[String]) = {
    val outputLabelToRule: Map[String, String] =
      protoDump.ruleOutputsByTarget.flatMap { case (rule, outputs) =>
        outputs.map(_ -> rule)
      }
    def isGenerated(label: String): Boolean =
      protoDump.ruleClassesByTarget.contains(label) ||
        outputLabelToRule.contains(label)
    def outputsOf(label: String): List[String] =
      protoDump.ruleClassesByTarget
        .get(label)
        .map(_ => protoDump.ruleOutputsByTarget.getOrElse(label, Nil))
        .getOrElse(List(label))

    val genLabels = srcsByTarget.values.flatten.toSet.filter(isGenerated)
    val genSrcOutputsByTarget = srcsByTarget.flatMap { case (target, labels) =>
      val genPaths = for {
        label <- labels
        if genLabels(label)
        output <- outputsOf(label)
        relative <- BazelLabels.fileLabelToWorkspaceRelativePath(output)
      } yield s"bazel-bin/$relative"
      Option.when(genPaths.nonEmpty)(target -> genPaths)
    }
    (genSrcOutputsByTarget, genLabels)
  }

}
