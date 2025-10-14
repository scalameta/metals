package scala.meta.internal.metals.mcp

import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration._

import scala.meta._
import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.Directories
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ScalaVersionSelector
import scala.meta.internal.metals.ScalaVersions
import scala.meta.internal.metals.ScalafixProvider
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.mcp.ScalafixLlmRuleProvider.RunResult
import scala.meta.internal.metals.scalacli.ScalaCli
import scala.meta.io.AbsolutePath

import coursierapi.Dependency
import org.eclipse.lsp4j.ApplyWorkspaceEditParams
import org.eclipse.lsp4j.WorkspaceEdit

class ScalafixLlmRuleProvider(
    workspace: AbsolutePath,
    scalafixProvider: ScalafixProvider,
    userConfig: () => UserConfiguration,
    metalsClient: MetalsLanguageClient,
    buildTargets: BuildTargets,
    scalaVersionSelector: ScalaVersionSelector,
)(implicit ec: ExecutionContext) {
  private val rulesDirectory = workspace.resolve(Directories.rules)
  private case class ScalafixRule(name: String, dep: Dependency)
  private def ruleLayout(
      ruleName: String,
      scalaVersion: String,
      ruleImplementation: String,
  ): String = {
    val scalaBinaryVersion =
      ScalaVersions.scalaBinaryVersionFromFullVersion(scalaVersion)
    val versionToUse =
      if (scalaBinaryVersion == "3") "2.13" else scalaBinaryVersion
    s"""|
        |//> using scala $scalaVersion
        |//> using dep "ch.epfl.scala:scalafix-core_$versionToUse:0.14.3"
        |//> using publish.organization "com.github.metals"
        |//> using publish.name "$ruleName"
        |//> using publish.version "0.1.0-SNAPSHOT"
        |//> using test.resourceDir ./resources
        |
        |$ruleImplementation
        |
        |""".stripMargin
  }

  private def ruleNameFromSource(
      parsed: Source,
      ruleImplementation: String,
  ): Either[String, String] =
    parsed
      .collect {
        case Init.After_4_6_0(
              Type.Name("SemanticRule"),
              _,
              List(
                Term.ArgClause(
                  List(
                    Lit.String(name)
                  ),
                  _,
                )
              ),
            ) =>
          name
      }
      .headOption
      .toRight(s"Could not detect rule name in:\n $ruleImplementation")

  private def classNameFromSource(
      ruleImplementation: String,
      parsed: Source,
  ): Either[String, String] = {

    val stats = parsed match {
      case Source(List(Pkg(_, stats))) => stats
      case Source(stats) => stats
    }
    stats.collectFirst { case cls: Defn.Class =>
      cls.name.syntax
    } match {
      case Some(clsName) =>
        Right(clsName)
      case None =>
        Left(s"Could not detect class name in:\n $ruleImplementation")
    }
  }

  private def packageNameFromSource(parsed: Source): String =
    parsed match {
      case Source(List(Pkg(name, _))) =>
        name.syntax
      case _ =>
        ""
    }

  private def publishRule(
      scalaVersion: String,
      ruleImplementation: String,
      description: String,
  ): Either[String, ScalafixRule] = {

    val parsedImplementation = ruleImplementation.parse[Source] match {
      case Parsed.Success(source) => Right(source)
      case Parsed.Error(pos, message, details) =>
        Left(s"Error parsing rule: $message")
    }

    val result = for {
      parsed <- parsedImplementation
      packageName = packageNameFromSource(parsed)
      className <- classNameFromSource(ruleImplementation, parsed)
      ruleName <- ruleNameFromSource(parsed, ruleImplementation)
    } yield {
      val ruleContents = ruleLayout(ruleName, scalaVersion, ruleImplementation)
      val ruleDir = rulesDirectory.resolve(ruleName)
      val rulesFile = ruleDir.resolve(s"$className.scala")
      rulesFile.writeText(ruleContents)

      scribe.debug(s"Wrote the rule to $rulesFile")

      val metadataFile =
        ruleDir.resolve(s"resources/META-INF/services/scalafix.v1.Rule")
      metadataFile.writeText(s"$packageName.$className")
      scribe.debug(s"Wrote the rule definition to $metadataFile")

      val readmeFile = ruleDir.resolve("README.md")
      val binaryVersion =
        ScalaVersions.scalaBinaryVersionFromFullVersion(scalaVersion)
      val scalaCli =
        ScalaCli.localScalaCli(userConfig()).getOrElse(ScalaCli.jvmBased())

      val publishCommand = scalaCli.command.toList ++ List(
        "--power",
        "publish",
        "local",
        ruleDir.toString(),
      )
      scribe.info(
        s"Publishing rule with command: $publishCommand"
      )
      val errorReporting = new StringBuilder()
      val result = ShellRunner.runSync(
        publishCommand,
        workspace,
        redirectErrorOutput = false,
        processErr = { err =>
          scribe.error(err)
          errorReporting.append(err + "\n")
        },
        timeout = 1.minute,
      )
      result match {
        case Some(_) =>
          readmeFile.writeText(description)
          Right(
            ScalafixRule(
              ruleName,
              ScalafixLlmRuleProvider.createDependency(ruleName, binaryVersion),
            )
          )
        case None =>
          readmeFile.deleteIfExists()
          Left(s"Error publishing rule: ${errorReporting.toString()}")

      }
    }
    result.flatten
  }

  def runOnAllTargets(
      ruleImplementation: String,
      description: String,
      targets: List[String],
      files: List[AbsolutePath],
  ): Future[Either[String, RunResult]] = {
    val publishedBuffer = TrieMap.empty[String, Dependency]
    val allTargets = targets.flatMap { targetName =>
      buildTargets
        .findByDisplayName(targetName)
        .flatMap(bd => buildTargets.scalaTarget(bd.getId()))
        .collect {
          case scalaTarget if !scalaTarget.isSbt => scalaTarget
        }
    }
    val allScalaVersions = allTargets.map(_.scalaVersion).toSet
    def loop(
        scalaVersions: List[String],
        name: String,
    ): Either[String, String] =
      scalaVersions match {
        case scalaVersion :: next =>
          publishRule(
            scalaVersion,
            ruleImplementation,
            description,
          ) match {
            case Left(value) => Left(value)
            case Right(value) =>
              publishedBuffer.put(scalaVersion, value.dep)
              loop(next, value.name)
          }
        case Nil => Right(name)
      }
    loop(allScalaVersions.toList, "") match {
      case Left(error) => Future.successful(Left(error))
      case Right(publishedRuleName) =>
        runScalafixRuleForAllTargets(publishedRuleName, files)
          .map { changeWasApplied =>
            if (changeWasApplied) {
              Right(RunResult(publishedRuleName, changeWasApplied))
            } else {
              Left("No changes were made for rule " + publishedRuleName)
            }
          }
          .recover { case error =>
            Left(error.getMessage)
          }
    }
  }

  def runScalafixRuleForAllTargets(
      ruleName: String,
      runOnSources: List[AbsolutePath] = Nil,
  ): Future[Boolean] = {
    val allTargets =
      buildTargets.allBuildTargetIds.map(buildTargets.scalaTarget).collect {
        case Some(scalaTarget) if !scalaTarget.isSbt => scalaTarget
      }
    val allFutures = allTargets.iterator.map { scalaTarget =>
      val sources =
        if (runOnSources.isEmpty) {
          buildTargets.buildTargetSources(scalaTarget.id).toList
        } else {
          runOnSources
        }
      val scalaBinaryVersion = ScalaVersions.scalaBinaryVersionFromFullVersion(
        scalaTarget.scalaVersion
      )
      val dependency =
        ruleDependencyIfNeeded(ruleName, scalaBinaryVersion)
      runScalafixRule(
        ruleName,
        sources,
        dependency,
      )
    }
    Future.sequence(allFutures.toList).map(results => results.exists(identity))
  }

  def runScalafixRule(
      ruleName: String,
      source: AbsolutePath,
  ): Future[Unit] = {
    val rule = ScalafixLlmRuleProvider.allRules(workspace).get(ruleName)
    rule match {
      case Some(value) =>
        val scalaVersion = scalaVersionSelector.scalaVersionForPath(source)
        val binaryVersion =
          ScalaVersions.scalaBinaryVersionFromFullVersion(scalaVersion)
        val dependency = ruleDependencyIfNeeded(ruleName, binaryVersion)
        runScalafixRule(ruleName, List(source), dependency).map(Right(_))
      case None =>
        Future.failed(new RuntimeException(s"Rule $ruleName not found"))
    }
  }

  private def ruleDependencyIfNeeded(
      ruleName: String,
      binaryVersion: String,
  ): Option[Dependency] = {
    ScalafixLlmRuleProvider.curatedRules.get(ruleName) match {
      case Some(value) => None
      case None =>
        Some(ScalafixLlmRuleProvider.createDependency(ruleName, binaryVersion))
    }
  }

  private def runScalafixRule(
      ruleName: String,
      sources: List[AbsolutePath],
      publishedRule: Option[Dependency],
  ): Future[Boolean] = {
    val all =
      sources.filter(file => file.filename.isScala).map { file =>
        val ruleRun = publishedRule match {
          case Some(dependency) =>
            scalafixProvider.runRuleFromDep(file, ruleName, dependency)
          case None =>
            scalafixProvider.runRulesOrPrompt(file, List(ruleName))
        }
        ruleRun
          .map { edits =>
            Map(file.toURI.toString -> edits.asJava)
          }
      }
    Future.sequence(all.toList).flatMap { edits =>
      val allEdits = edits.flatten.toMap
      if (allEdits.exists(!_._2.isEmpty())) {
        metalsClient
          .applyEdit(
            new ApplyWorkspaceEditParams(new WorkspaceEdit(allEdits.asJava))
          )
          .asScala
          .map(_ => true)
      } else {
        Future.successful(false)
      }
    }
  }

}

object ScalafixLlmRuleProvider {

  def allRules(workspace: AbsolutePath): Map[String, String] = {
    generatedRules(workspace) ++ curatedRules
  }

  def additionalDependencies(
      ruleNames: List[String],
      binaryVersion: String,
  ): Map[String, Dependency] = {
    ruleNames
      .map(ruleName => ruleName -> createDependency(ruleName, binaryVersion))
      .toMap
  }

  def createDependency(
      ruleName: String,
      binaryVersion: String,
  ): Dependency =
    Dependency.of(
      s"com.github.metals",
      s"${ruleName}_$binaryVersion",
      "0.1.0-SNAPSHOT",
    )

  def generatedRules(workspace: AbsolutePath): Map[String, String] = {
    val rulesDir = workspace.resolve(Directories.rules)
    val rules = if (rulesDir.exists && rulesDir.isDirectory) {
      rulesDir.list
        .filter(_.isDirectory)
        .map { ruleDir =>
          val ruleName = ruleDir.filename
          val readmeFile = ruleDir.resolve("README.md")
          val description = if (readmeFile.exists) {
            readmeFile.readTextOpt.getOrElse(ruleName)
          } else {
            ruleName
          }
          ruleName -> description
        }
        .toSeq
        .toMap
    } else Map.empty[String, String]
    rules
  }

  case class RunResult(ruleName: String, changeWasApplied: Boolean)
  // Curated list of rules that LLMs can use
  def curatedRules: Map[String, String] = {
    Map(
      "ExplicitResultTypes" -> "Inserts type annotations for inferred public members.",
      "OrganizeImports" -> "Organize import statements, used for source.organizeImports code action",
      "RemoveUnused" -> "Removes unused imports and terms that reported by the compiler under -Wunused",
      "ProcedureSyntax" -> "Replaces deprecated Scala 2.x procedure syntax with explicit ': Unit ='",
      "RedundantSyntax" -> "Removes redundant syntax such as `final` modifiers on an object",
    )
  }
}
