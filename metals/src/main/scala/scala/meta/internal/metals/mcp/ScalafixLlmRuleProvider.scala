package scala.meta.internal.metals.mcp

import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration._

import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.Directories
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ScalaVersionSelector
import scala.meta.internal.metals.ScalaVersions
import scala.meta.internal.metals.ScalafixProvider
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
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

  private def layout(
      ruleName: String,
      scalaVersion: String,
      ruleImplementation: String,
  ): String =
    s"""|
        |//> using scala $scalaVersion
        |//> using dep "ch.epfl.scala:scalafix-core_2.13:0.14.3"
        |//> using publish.organization "com.github.metals"
        |//> using publish.name "$ruleName"
        |//> using publish.version "0.1.0-SNAPSHOT"
        |//> using test.resourceDir ./resources
        |
        |$ruleImplementation
        |
        |""".stripMargin

  private def publishRule(
      ruleName: String,
      scalaVersion: String,
      ruleImplementation: String,
      description: String,
  ): Either[String, Dependency] = {
    val ruleContents = layout(ruleName, scalaVersion, ruleImplementation)
    import scala.meta._
    val checkPackage = ruleContents.parse[Source] match {
      case Parsed.Success(source) => source
      case Parsed.Error(pos, message, details) =>
        throw details
    }
    val ruleNameToUse = checkPackage match {
      case Source(List(Pkg(name, _))) => name.syntax + "." + ruleName
      case otherwise =>
        scribe.debug(s"No package found in rule: ${otherwise.syntax}")
        ruleName
    }
    val ruleDir = rulesDirectory.resolve(ruleName)
    val rulesFile = ruleDir.resolve(s"$ruleName.scala")
    scribe.debug(s"Wrote the rule to $rulesFile")
    rulesFile.writeText(ruleContents)

    val metadataFile =
      ruleDir.resolve(s"resources/META-INF/services/scalafix.v1.Rule")
    metadataFile.writeText(s"$ruleNameToUse")
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
        Right(createDependency(ruleName, binaryVersion))
      case None =>
        readmeFile.deleteIfExists()
        Left(s"Error publishing rule: ${errorReporting.toString()}")

    }
  }

  def runOnAllTargets(
      ruleName: String,
      ruleImplementation: String,
      description: String,
  ): Either[String, Future[Unit]] = {
    val publishedBuffer = TrieMap.empty[String, Dependency]
    val allTargets =
      buildTargets.allBuildTargetIds.map(buildTargets.scalaTarget).collect {
        case Some(scalaTarget) if !scalaTarget.isSbt => scalaTarget
      }
    val allScalaVersions = allTargets.map(_.scalaVersion).toSet
    def loop(scalaVersions: List[String]): Either[String, Future[Unit]] =
      scalaVersions match {
        case scalaVersion :: next =>
          publishRule(
            ruleName,
            scalaVersion,
            ruleImplementation,
            description,
          ) match {
            case Left(value) => Left(value)
            case Right(value) =>
              publishedBuffer.put(scalaVersion, value)
              loop(next)
          }
        case Nil => Right(Future.unit)
      }
    loop(allScalaVersions.toList) match {
      case Left(value) => Left(value)
      case Right(value) => Right(runScalafixRuleForAllTargets(ruleName))
    }
  }

  def allRules: Map[String, String] = {
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
    rules ++ ScalafixLlmRuleProvider.curatedRules
  }

  def runScalafixRuleForAllTargets(
      ruleName: String
  ): Future[Unit] = {
    val allTargets =
      buildTargets.allBuildTargetIds.map(buildTargets.scalaTarget).collect {
        case Some(scalaTarget) if !scalaTarget.isSbt => scalaTarget
      }
    val allFutures = allTargets.iterator.map { scalaTarget =>
      val sources = buildTargets.buildTargetSources(scalaTarget.id).toList
      val dependency =
        ruleDependencyIfNeeded(ruleName, scalaTarget.scalaBinaryVersion)
      runScalafixRule(
        ruleName,
        sources,
        dependency,
      )
    }
    Future.sequence(allFutures.toList).map(_ => ())
  }

  def runScalafixRule(
      ruleName: String,
      source: AbsolutePath,
  ): Future[Unit] = {
    val rule = allRules.get(ruleName)
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
      case None => Some(createDependency(ruleName, binaryVersion))
    }
  }

  private def createDependency(
      ruleName: String,
      binaryVersion: String,
  ): Dependency =
    Dependency.of(
      s"com.github.metals",
      s"${ruleName}_$binaryVersion",
      "0.1.0-SNAPSHOT",
    )

  private def runScalafixRule(
      ruleName: String,
      sources: List[AbsolutePath],
      publishedRule: Option[Dependency],
  ): Future[Unit] = {
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
    Future.sequence(all.toList).map { edits =>
      val allEdits = edits.flatten.toMap
      if (allEdits.nonEmpty) {
        metalsClient.applyEdit(
          new ApplyWorkspaceEditParams(new WorkspaceEdit(allEdits.asJava))
        )
      } else {
        throw new RuntimeException(s"No changes were made for rule $ruleName")
      }
    }
  }

}

object ScalafixLlmRuleProvider {
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
