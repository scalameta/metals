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
import scala.meta.internal.semver.SemVer.Version
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
      targetsXmlQueryOutput <- BazelQuery
        .fullInformationQuery(targets)
        .run(queryEnv)
      targetsXmlDump = new BazelTargetsXmlDump(targetsXmlQueryOutput)
      srcs = targetsXmlDump.getLabels("srcs")
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
      externalDepModules = matchExternalDepsToModules(
        externalDeps,
        dependencyModules,
        repositoryName,
      )
      scalaVersions = targetsXmlDump.getStrings("scala_version")
      effectiveScalaVersionValue =
        parseScalaVersionFromBuildFiles()
          .orElse(
            scalaVersions.values.flatten.toSeq.maxByOption(Version.fromString)
          )
      scalaVersionByTarget = targets.map { target =>
        val targetScalaVersion = scalaVersions
          .get(target)
          .flatMap(_.maxByOption(Version.fromString))
          .orElse(effectiveScalaVersionValue)
        target -> targetScalaVersion
      }.toMap
      selectAwareSrcsOutput <- BazelQuery
        .selectAwareSrcsQuery(targets)
        .run(queryEnv)
      inactiveSourceVersions = BazelBuildSrcs.inactiveSourceVersions(
        selectAwareSrcsOutput,
        scalaVersionByTarget,
      )
      _ = scribe.info(
        s"bazel-mbt: ${inactiveSourceVersions.size} version-specific sources " +
          "from inactive select() branches"
      )
      build = BazelMbtBuildSupport.fromDiscovery(
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
        inactiveSourceVersions,
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

  private def matchExternalDepsToModules(
      externalDeps: Map[String, List[String]],
      dependencyModules: Seq[MbtDependencyModule],
      repositoryName: String,
  ): Map[String, List[String]] = {
    val modulesByBazelLabel = dependencyModules.flatMap { module =>
      bazelLabelFromModuleId(module.id, repositoryName).map(_ -> module.id)
    }.toMap

    externalDeps.map { case (target, deps) =>
      val matchedModuleIds = for {
        dep <- deps
        normalizedDep = normalizeBazelLabel(dep)
        moduleId <- modulesByBazelLabel.get(normalizedDep)
      } yield moduleId
      target -> matchedModuleIds
    }
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

  private def parseScalaVersionFromBuildFiles(): Option[String] = {
    val versionPattern = """(?i)scala_version\s*=\s*["'](\d+\.\d+\.\d+)["']""".r
    val moduleFile = projectRoot.resolve("MODULE.bazel")
    val workspaceFile = projectRoot.resolve("WORKSPACE")

    def extractFromFile(path: AbsolutePath): Option[String] =
      if (Files.exists(path.toNIO)) {
        val content = new String(Files.readAllBytes(path.toNIO))
        versionPattern.findFirstMatchIn(content).map(_.group(1))
      } else None

    extractFromFile(moduleFile).orElse(extractFromFile(workspaceFile))
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
