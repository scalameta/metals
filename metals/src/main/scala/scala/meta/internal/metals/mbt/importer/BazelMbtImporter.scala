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
      deps = queryDeps(targets.toSet, targets, targetsXmlDump)
      externalDeps = targetsXmlDump.externalDepsByTarget(targets)
      externalDepModules = BazelMavenJsonImporter.matchExternalDeps(
        externalDeps,
        dependencyModules,
        mavenHubs,
      )
      scalaVersionByNamespace <- resolveScalaVersionByNamespace(
        namespaceMode,
        targets,
        targetsXmlDump,
        externalDeps,
        outputBase,
      )
      build = BazelMbtBuildSupport.fromDiscovery(
        namespaceMode,
        targets,
        filteredSrcs,
        scalacOptions,
        javacOptions,
        deps,
        externalDepModules,
        runTargets,
        classDirectories,
        dependencyModules,
        scalaVersionByNamespace,
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
      targetsXml: BazelTargetsXmlDump,
  ): Map[String, List[String]] = {
    orderedTargets.map { target =>
      target -> targetsXml.depsByTarget.getOrElse(target, Nil).filter(targetSet)
    }.toMap
  }

  private def bazelLabelFromModuleId(
      moduleId: String,
      repositoryName: String,
  ): Option[String] = {
    val parts = moduleId.split(":")
    if (parts.length >= 2) {
      val groupId = parts(0)
      val artifactId = parts(1)
      val sanitizedGroup = groupId.replace('.', '_').replace('-', '_')
      val sanitizedArtifact = artifactId.replace('.', '_').replace('-', '_')
      Some(s"@$repositoryName//:${sanitizedGroup}_$sanitizedArtifact")
    } else None
  }

  private def normalizeBazelLabel(label: String): String = {
    val withoutDoubleAt =
      if (label.startsWith("@@")) label.substring(1) else label
    withoutDoubleAt.replaceAll("~[^/]+", "")
  }

  private def resolveScalaVersionByNamespace(
      namespaceMode: BazelMbtNamespaceMode,
      targets: List[String],
      targetsXmlDump: BazelTargetsXmlDump,
      externalDeps: Map[String, List[String]],
      outputBase: Option[Path],
  ): Future[Map[String, Option[String]]] = {
    val scalaVersionAttributes = targetsXmlDump.getStrings("scala_version")
    val namespaceToTargets = targets.groupBy(
      BazelMbtBuildSupport.namespaceKey(namespaceMode, _)
    )
    val versionsByNamespace =
      namespaceToTargets.map { case (ns, nsTargets) =>
        val explicitVersions =
          nsTargets.flatMap(t => scalaVersionAttributes.getOrElse(t, Nil))
        val version = pickMaxScalaVersion(
          if (explicitVersions.nonEmpty) explicitVersions
          else
            nsTargets
              .flatMap(t => externalDeps.getOrElse(t, Nil))
              .flatMap(extractScalaVersionFromLabel)
        )
        ns -> version
      }
    val namespacesNeedingFallback =
      versionsByNamespace.collect { case (ns, None) => ns }
    val globalFallback =
      if (namespacesNeedingFallback.isEmpty) Future.successful(None)
      else
        parseScalaVersionFromRulesScalaConfig(outputBase)
          .orElse(parseScalaVersionFromBuildFiles())
          .map(v => Future.successful(Some(v)))
          .getOrElse(queryScalaVersionFromDeps())

    globalFallback.map { fallbackVersion =>
      versionsByNamespace.map { case (ns, v) =>
        ns -> v.orElse(fallbackVersion)
      }
    }
  }

  private def parseScalaVersionFromRulesScalaConfig(
      outputBase: Option[Path]
  ): Option[String] = {
    val versionPattern = """SCALA_VERSION\s*=\s*["'](\d+\.\d+\.\d+)["']""".r
    outputBase.flatMap { base =>
      val externalDir = base.resolve("external").toFile
      val entries = externalDir.listFiles()
      val configDir = Option(entries).flatMap(
        _.find(f => f.isDirectory && f.getName.contains("rules_scala_config"))
          .map(_.toPath)
      )
      configDir.flatMap { dir =>
        val configFile = dir.resolve("config.bzl")
        if (Files.exists(configFile)) {
          val content = new String(Files.readAllBytes(configFile))
          versionPattern.findFirstMatchIn(content).map(_.group(1))
        } else None
      }
    }
  }

  private def queryScalaVersionFromDeps(): Future[Option[String]] =
    BazelQuery.allScalaLibrariesQuery.run(queryEnv).map { queryOutput =>
      pickMaxScalaVersion(
        asLines(queryOutput).flatMap(extractScalaVersionFromLabel)
      )
    }

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

  private def pickMaxScalaVersion(versions: List[String]): Option[String] =
    versions.maxByOption { v =>
      val p = v.split('.')
      (
        p.lift(0).flatMap(_.toIntOption).getOrElse(0),
        p.lift(1).flatMap(_.toIntOption).getOrElse(0),
        p.lift(2).flatMap(_.toIntOption).getOrElse(0),
      )
    }

  private def extractScalaVersionFromLabel(label: String): Option[String] = {
    val versionPattern =
      """scala(?:3-library_3|[_-]library)[_-](\d+\.\d+\.\d+)""".r
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
