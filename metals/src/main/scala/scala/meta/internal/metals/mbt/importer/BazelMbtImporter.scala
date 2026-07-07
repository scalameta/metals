package scala.meta.internal.metals.mbt.importer

import java.nio.file.Files
import java.nio.file.Path

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.xml.XML

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
      repositoryName = BazelMavenJsonImporter
        .extractRepositoryNameFromBazelConfig(projectRoot)
      _ = scribe.info(s"bazel-mbt: found repository name: $repositoryName")
      dependencyModules = BazelMavenJsonImporter
        .importMaven(projectRoot, outputBase, repositoryName)
      targets <- runRuleTargetsQuery(patterns)
      _ = scribe.info(s"bazel-mbt: found ${targets.size} targets")
      srcs <- querySrcs(targets)
      scalacOptions <- queryScalacOptions(targets)
      javacOptions <- queryJavacOptions(targets)
      deps <- queryDeps(targets.toSet, targets)
      externalDeps <- queryExternalDeps(targets)
      externalDepModules = matchExternalDepsToModules(
        externalDeps,
        dependencyModules,
        repositoryName,
      )
      scalaVersionFromDeps <- queryScalaVersionFromDeps(targets)
      effectiveScalaVersion <- scalaVersionFromDeps match {
        case Some(value) => Future.successful(Some(value))
        case None => queryScalaVersion(targets)
      }
      build = BazelMbtBuildSupport.fromDiscovery(
        namespaceMode,
        targets,
        srcs,
        scalacOptions,
        javacOptions,
        deps,
        externalDepModules,
        dependencyModules,
        effectiveScalaVersion,
      )
      _ <- Future(Files.writeString(out.toNIO, MbtBuild.toJson(build)))
    } yield ()
  }

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

  private val ruleKinds: List[String] =
    List(
      "scala_library", "java_library", "scala_binary", "java_binary",
      "scala_test", "java_test",
    )

  private def buildRuleKindsQuery(patterns: List[String]): String = {
    val ps =
      if (patterns.isEmpty) BazelProjectViewTargets.defaultPatterns
      else patterns
    val parts = for {
      k <- ruleKinds
      p <- ps
    } yield s"kind($k, $p)"
    parts.mkString(" union ")
  }

  private def runRuleTargetsQuery(
      patterns: List[String]
  ): Future[List[String]] =
    runBazelQueryLines(buildRuleKindsQuery(patterns))

  private def querySrcs(
      targets: List[String]
  ): Future[Map[String, List[String]]] =
    if (targets.isEmpty) Future.successful(Map.empty)
    else {
      runBazelQueryXml(s"set(${targets.mkString(" ")})")
        .map {
          BazelMbtImporter.labelsFromQueryXml(_, "srcs")
        }
    }

  private def queryScalacOptions(
      targets: List[String]
  ): Future[Map[String, List[String]]] =
    if (targets.isEmpty) Future.successful(Map.empty)
    else {
      runBazelQueryXml(s"set(${targets.mkString(" ")})")
        .map(BazelMbtImporter.stringsFromQueryXml(_, "scalacopts"))
    }

  private def queryJavacOptions(
      targets: List[String]
  ): Future[Map[String, List[String]]] =
    if (targets.isEmpty) Future.successful(Map.empty)
    else {
      runBazelQueryXml(s"set(${targets.mkString(" ")})")
        .map(BazelMbtImporter.stringsFromQueryXml(_, "javacopts"))
    }

  private def queryDeps(
      targetSet: Set[String],
      orderedTargets: List[String],
  ): Future[Map[String, List[String]]] =
    if (orderedTargets.isEmpty) Future.successful(Map.empty)
    else {
      runBazelQueryXml(s"set(${orderedTargets.mkString(" ")})")
        .map { xml =>
          val depsByTarget = BazelMbtImporter.depsFromQueryXml(xml)
          orderedTargets.map { target =>
            target -> depsByTarget.getOrElse(target, Nil).filter(targetSet)
          }.toMap
        }
    }

  private def queryExternalDeps(
      targets: List[String]
  ): Future[Map[String, List[String]]] =
    if (targets.isEmpty) Future.successful(Map.empty)
    else {
      runBazelQueryXml(s"deps(set(${targets.mkString(" ")}))")
        .map { xml =>
          BazelMbtImporter
            .reachableLabelsFromQueryXml(xml, targets)
            .map { case (target, deps) =>
              target -> deps.filter(isExternalDep)
            }
        }
    }

  private def isExternalDep(label: String): Boolean =
    label.startsWith("@") && !label.startsWith("@@")

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

  private def queryScalaVersionFromDeps(
      targets: List[String]
  ): Future[Option[String]] =
    if (targets.isEmpty) Future.successful(None)
    else
      runBazelQueryLines(
        s"filter('scala.library', deps(set(${targets.mkString(" ")})))"
      ).map { lines =>
        lines.flatMap(extractScalaVersionFromLabel).headOption
      }

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

  private def queryOutputBase(): Future[Option[Path]] = {
    val buf = new StringBuilder()
    shellRunner
      .run(
        "bazel-info",
        List("bazel", "info", "output_base"),
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

  private def runBazelQueryLines(query: String): Future[List[String]] =
    runBazelQueryOutput(query, "label").map { output =>
      output.linesIterator.map(_.trim).filter(_.nonEmpty).toList
    }

  private def runBazelQueryXml(query: String): Future[String] =
    runBazelQueryOutput(query, "xml")

  private def runBazelQueryOutput(
      query: String,
      output: String,
  ): Future[String] = {
    val buf = new StringBuilder()
    shellRunner
      .run(
        "bazel-mbt-query",
        List(
          "bazel",
          "query",
          query,
          s"--output=$output",
          "--keep_going",
        ),
        projectRoot,
        redirectErrorOutput = false,
        javaHome = userConfig().javaHome,
        processOut = line => {
          buf.append(line)
          buf.append(System.lineSeparator())
        },
        processErr = scribe.warn(_),
      )
      .future
      .flatMap {
        case ExitCodes.Success =>
          Future.successful(buf.toString)
        case ExitCodes.Cancel =>
          Future.failed(
            new java.util.concurrent.CancellationException(
              "bazel-mbt: query cancelled"
            )
          )
        case 3 =>
          scribe.warn(
            s"bazel-mbt: bazel query had errors (exit code 3), results may be incomplete"
          )
          Future.successful(buf.toString)
        case code =>
          Future.failed(
            new Exception(s"bazel-mbt: bazel query failed with exit code $code")
          )
      }
  }

}

object BazelMbtImporter {

  private[importer] def depsFromQueryXml(
      xml: String
  ): Map[String, List[String]] = {
    val root = XML.loadString(xml)
    val targetLabels = for {
      rule <- root \\ "rule"
      target = (rule \ "@name").text
      if target.nonEmpty
    } yield {
      val ruleInputs = for {
        input <- rule \ "rule-input"
        value = (input \ "@name").text
        if value.nonEmpty
      } yield value
      val labels = (ruleInputs ++ labelsFromRuleAttribute(rule, None)).distinct
      target -> labels.toList
    }
    targetLabels.toMap
  }

  private[importer] def reachableLabelsFromQueryXml(
      xml: String,
      roots: List[String],
  ): Map[String, List[String]] = {
    val adjacency = depsFromQueryXml(xml)
    roots.map { root =>
      root -> reachableLabels(root, adjacency).filterNot(_ == root)
    }.toMap
  }

  private def labelsFromQueryXml(
      xml: String,
      attributeName: String,
  ): Map[String, List[String]] = {
    val root = XML.loadString(xml)
    val targetLabels = for {
      rule <- root \\ "rule"
      target = (rule \ "@name").text
      if target.nonEmpty
    } yield target -> labelsFromRuleAttribute(rule, Some(attributeName)).toList
    targetLabels.toMap
  }

  private def stringsFromQueryXml(
      xml: String,
      attributeName: String,
  ): Map[String, List[String]] = {
    val root = XML.loadString(xml)
    val targetLabels = for {
      rule <- root \\ "rule"
      target = (rule \ "@name").text
      if target.nonEmpty
    } yield target -> stringsFromRuleAttribute(rule, attributeName).toList
    targetLabels.toMap
  }

  private def stringsFromRuleAttribute(
      rule: scala.xml.Node,
      attributeName: String,
  ): Seq[String] =
    for {
      attribute <- rule \ "_"
      name = (attribute \ "@name").text
      if name == attributeName
      string <- attribute \\ "string"
      value = (string \ "@value").text
      if value.nonEmpty
    } yield value

  private def labelsFromRuleAttribute(
      rule: scala.xml.Node,
      attributeName: Option[String],
  ): Seq[String] =
    for {
      attribute <- rule \ "_"
      name = (attribute \ "@name").text
      if attributeName.forall(_ == name)
      label <- attribute \\ "label"
      value = (label \ "@value").text
      if value.nonEmpty
    } yield value

  private def reachableLabels(
      root: String,
      adjacency: Map[String, List[String]],
  ): List[String] = {
    val seen = scala.collection.mutable.LinkedHashSet.empty[String]
    val queue = scala.collection.mutable.Queue(root)
    while (queue.nonEmpty) {
      val current = queue.dequeue()
      if (!seen(current)) {
        seen += current
        for (dep <- adjacency.getOrElse(current, Nil)) {
          if (!seen(dep)) queue.enqueue(dep)
        }
      }
    }
    seen.toList
  }
}
