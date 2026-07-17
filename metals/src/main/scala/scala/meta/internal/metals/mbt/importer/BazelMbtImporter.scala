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
      effectiveScalaVersion = BazelEffectiveScalaVersionResolver.resolve(
        outputBase,
        maven.dependencyModules,
        protoDump.getStrings("scala_version"),
        userConfig(),
      )
      _ <- assembleAndWrite(
        workspace,
        namespaceMode,
        out,
        targets,
        protoDump,
        bazelBin,
        effectiveScalaVersion,
        maven,
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

  private def assembleAndWrite(
      workspace: AbsolutePath,
      namespaceMode: BazelMbtNamespaceMode,
      out: AbsolutePath,
      targets: List[String],
      protoDump: BazelTargetsProtoDump,
      bazelBin: Option[Path],
      effectiveScalaVersion: Option[String],
      maven: MavenResolution,
  ): Future[Unit] = {
    val srcs = protoDump.srcLabelsByTarget
    val (genSrcOutputsByTarget, genSrcLabels) =
      if (userConfig().importGeneratedSourcesMbt)
        computeGenSrcOutputs(srcs, protoDump)
      else
        (Map.empty[String, List[String]], Set.empty[String])
    val filteredSrcs = srcs.map { case (t, labels) =>
      t -> labels.filterNot(genSrcLabels)
    }
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
    val build = BazelMbtBuildSupport.fromDiscovery(
      namespaceMode,
      targets,
      filteredSrcs,
      scalacOptions,
      javacOptions,
      deps,
      allExternalDepModules,
      runTargets,
      classDirectories,
      allDependencyModules,
      effectiveScalaVersion,
      genSrcOutputsByTarget,
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
