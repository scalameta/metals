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
      mavenHubs = BazelMavenJsonImporter
        .discoverMavenHubs(
          BazelMavenJsonImporter.externalDirs(
            projectRoot,
            outputBase.map(AbsolutePath.apply),
          )
        )
      _ = scribe.info(
        s"bazel-mbt: found maven hubs: ${mavenHubs.map(_.name.value).mkString(", ")}"
      )
      mavenImportStart = System.nanoTime()
      dependencyModules = BazelMavenJsonImporter
        .importMaven(projectRoot, outputBase, mavenHubs)
      _ = scribe.debug(
        s"bazel-mbt: importMaven took ${(System.nanoTime() - mavenImportStart) / 1_000_000}ms"
      )
      ruleKindsQueryOutput <- BazelQuery
        .buildRuleKindsQuery(patterns)
        .run(queryEnv)
      targets = asLines(ruleKindsQueryOutput)
      _ = scribe.info(s"bazel-mbt: found ${targets.size} targets")
      targetsXmlQueryOutput <- BazelQuery
        .fullInformationQuery(targets)
        .run(queryEnv)
      targetsXmlDump = new BazelTargetsXmlDump(targetsXmlQueryOutput)
      srcs = targetsXmlDump.getLabels("srcs")
      (genSrcOutputsByTarget, genSrcLabels) <-
        if (userConfig().importGeneratedSourcesMbt)
          queryGenSrcOutputsByTarget(srcs)
        else
          Future.successful(
            (Map.empty[String, List[String]], Set.empty[String])
          )
      filteredSrcs = srcs.map { case (t, labels) =>
        t -> labels.filterNot(genSrcLabels)
      }
      scalacOptions = targetsXmlDump.getStrings("scalacopts")
      javacOptions = targetsXmlDump.getStrings("javacopts")
      runTargets = targets
        .filter(target =>
          targetsXmlDump.ruleClassesByTarget
            .get(target)
            .exists(isRunnableRule)
        )
        .toSet
      classDirectories = classDirectoriesForRunTargets(
        bazelBin,
        runTargets,
        targetsXmlDump.ruleOutputsByTarget,
      )
      targetSet = targets.toSet
      deps = queryDeps(targetSet, targets, targetsXmlDump)
      reachableLabelsByTarget = targetsXmlDump.reachableLabels(targets)
      externalDeps =
        targetsXmlDump.externalDepsByTarget(reachableLabelsByTarget)
      externalDepModules = BazelMavenJsonImporter.matchExternalDeps(
        externalDeps,
        dependencyModules,
        mavenHubs,
      )
      importTargets =
        targetSet.filter(targetsXmlDump.jarLabelsByImportTarget.contains)
      _ = scribe.info(s"bazel-mbt: import targets: $importTargets")
      importTargetJarLabels = importTargets.map { target =>
        target -> resolvedJarLabels(
          target,
          targetsXmlDump.jarLabelsByImportTarget,
          targetsXmlDump.ruleOutputsByTarget,
        )
      }.toMap
      importJarModules = buildImportJarModules(
        importTargetJarLabels,
        targetsXmlDump.sourcesJarByImportTarget,
        bazelBin,
      )
      importDepModules = buildImportModuleIdsByTarget(
        targets,
        importTargetJarLabels,
        reachableLabelsByTarget,
      )
      allDependencyModules = dependencyModules ++ importJarModules
      allExternalDepModules = mergeDepsModuleMaps(
        externalDepModules,
        importDepModules,
      )
      scalaVersionFromDeps <- queryScalaVersionFromDeps()
      effectiveScalaVersion <- scalaVersionFromDeps match {
        case Some(value) => Future.successful(Some(value))
        case None => queryScalaVersion(targets)
      }
      build = BazelMbtBuildSupport.fromDiscovery(
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
        relative <- BazelLabels.fileLabelToWorkspaceRelativePath(
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
      targetsXml: BazelTargetsXmlDump,
  ): Map[String, List[String]] = {
    orderedTargets.map { target =>
      target -> targetsXml.depsByTarget.getOrElse(target, Nil).filter(targetSet)
    }.toMap
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

  /**
   * A `java_import`/`scala_import`'s `jars` attribute can point at a
   * genrule or a raw file. We try to return the outputted jar of that genrule,
   * or, if it doesn't exist, we return it as if it was a raw file.
   */
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

  private def mergeDepsModuleMaps(
      maps: Map[String, List[String]]*
  ): Map[String, List[String]] = {
    val allKeys = maps.flatMap(_.keySet).toSet
    allKeys.map { key =>
      key -> maps.flatMap(_.getOrElse(key, Nil)).distinct.toList
    }.toMap
  }

  private def queryScalaVersionFromDeps(): Future[Option[String]] = for {
    queryOutput <- BazelQuery.allScalaLibrariesQuery.run(queryEnv)
    lines = asLines(queryOutput)
  } yield lines.flatMap(extractScalaVersionFromLabel).headOption

  private def queryScalaVersion(
      @annotation.nowarn("msg=never used") targets: List[String]
  ): Future[Option[String]] =
    Future.successful(parseScalaVersionFromBuildFiles())

  private def parseScalaVersionFromBuildFiles(): Option[String] = {
    val versionPattern = """scala_version\s*=\s*["'](\d+\.\d+\.\d+)["']""".r
    val moduleFile = projectRoot.resolve("MODULE.bazel")
    val workspaceFile = projectRoot.resolve("WORKSPACE")

    def extractFromFile(path: AbsolutePath): Option[String] =
      if (Files.exists(path.toNIO)) {
        val content = new String(Files.readAllBytes(path.toNIO))
        versionPattern.findFirstMatchIn(content).map(_.group(1))
      } else None

    extractFromFile(moduleFile).orElse(extractFromFile(workspaceFile))
  }

  private def extractScalaVersionFromLabel(label: String): Option[String] = {
    val versionPattern = """scala[_-]library[_-](\d+\.\d+\.\d+)""".r
    versionPattern.findFirstMatchIn(label).map(_.group(1))
  }

  /**
   * For any entry in `srcsByTarget` that is a Bazel rule (not a real source
   * file), runs `bazel cquery --output=files` on those rules and maps the
   * output file paths to `uncheckedSources` for the owning target.
   * Also returns the set of gen src labels so callers can exclude them from
   * `sources`.
   */
  private def queryGenSrcOutputsByTarget(
      srcsByTarget: Map[String, List[String]]
  ): Future[(Map[String, List[String]], Set[String])] = {
    val allSrcLabels =
      srcsByTarget.values.flatten.filter(_.startsWith("//")).toSet
    if (allSrcLabels.isEmpty) {
      Future.successful((Map.empty[String, List[String]], Set.empty[String]))
    } else {
      queryRuleLabels(allSrcLabels)
        .flatMap { ruleLabels =>
          if (ruleLabels.isEmpty)
            Future.successful(
              (Map.empty[String, List[String]], Set.empty[String])
            )
          else
            runBazelCqueryOutputsByLabel(ruleLabels).map { outputsByGenLabel =>
              (
                groupGenOutputsByTarget(
                  srcsByTarget,
                  ruleLabels,
                  outputsByGenLabel,
                ),
                ruleLabels,
              )
            }
        }
        .recover { case e =>
          scribe.warn(s"bazel-mbt: failed to query generated source outputs", e)
          (Map.empty[String, List[String]], Set.empty[String])
        }
    }
  }

  private def queryRuleLabels(srcLabels: Set[String]): Future[Set[String]] =
    BazelQuery(s"set(${srcLabels.mkString(" ")})", BazelQuery.OutputMode.Xml)
      .run(queryEnv)
      .map { xml =>
        val srcFiles = new BazelTargetsXmlDump(xml).sourceFileLabels
        srcLabels.filterNot(srcFiles)
      }

  private def groupGenOutputsByTarget(
      srcsByTarget: Map[String, List[String]],
      ruleLabels: Set[String],
      outputsByGenLabel: Map[String, List[String]],
  ): Map[String, List[String]] =
    srcsByTarget.flatMap { case (target, srcs) =>
      val genPaths = srcs
        .filter(ruleLabels)
        .flatMap(outputsByGenLabel.getOrElse(_, Nil))
      Option.when(genPaths.nonEmpty)(target -> genPaths)
    }

  /** bazel cquery that returns output file paths grouped by label. */
  private def runBazelCqueryOutputsByLabel(
      labels: Set[String]
  ): Future[Map[String, List[String]]] = {
    val starlarkExpr =
      """str(target.label) + "\t" + "\t".join([f.path for f in target.files.to_list()])"""
    BazelQuery(
      s"set(${labels.mkString(" ")})",
      BazelQuery.OutputMode.Starlark,
      extraArgs = List(s"--starlark:expr=$starlarkExpr"),
      queryType = BazelQuery.QueryType.CQuery,
    ).run(queryEnv)
      .map { output =>
        output.linesIterator
          .map(_.trim)
          .filter(_.nonEmpty)
          .flatMap { line =>
            line.split("\t") match {
              case Array(rawLabel, paths @ _*) =>
                val label =
                  if (rawLabel.startsWith("@@//")) rawLabel.substring(2)
                  else rawLabel
                Some(label -> paths.toList)
              case _ =>
                scribe.warn(
                  s"bazel-mbt: unexpected cquery starlark line: $line"
                )
                None
            }
          }
          .toMap
      }
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
