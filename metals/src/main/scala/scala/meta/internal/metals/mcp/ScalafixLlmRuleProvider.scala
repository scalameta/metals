package scala.meta.internal.metals.mcp

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.metals.MetalsEnrichments._
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
)(implicit ec: ExecutionContext) {
  private val rulesDirectory = workspace.resolve(".metals/rules")

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

    // TODO does semantic rule need a different file?
    val metadataFile =
      ruleDir.resolve(s"resources/META-INF/services/scalafix.v1.Rule")
    metadataFile.writeText(s"$ruleNameToUse")
    scribe.debug(s"Wrote the rule defintion to $rulesFile")

    val binaryVersion =
      ScalaVersions.scalaBinaryVersionFromFullVersion(scalaVersion)
    ScalaCli.localScalaCli(userConfig()) match {
      case Some(ScalaCli.ScalaCliCommand(command, _)) =>
        scribe.info(
          s"Publishing rule with command: ${command.toList ++ List("publish", "local", ruleDir.toString())}"
        )
        val errorReporting = new StringBuilder()
        // TODO compile per scala version
        val result = ShellRunner.runSync(
          command.toList ++ List("publish", "local", ruleDir.toString()),
          workspace,
          redirectErrorOutput = false,
          processErr = { err =>
            scribe.error(err)
            errorReporting.append(err + "\n")
          },
        )
        result match {
          case Some(_) =>
            Right(
              Dependency.of(
                s"com.github.metals",
                s"${ruleName}_$binaryVersion",
                "0.1.0-SNAPSHOT",
              )
            )
          case None =>
            Left(s"Error publishing rule: ${errorReporting.toString()}")
        }
      case None =>
        // TODO use jar based one
        Left("No scala-cli found")
    }
  }

  def runScalafixRule(
      ruleName: String,
      scalaVersion: String,
      ruleImplementation: String,
  ): Either[String, Future[Unit]] = {
    val publishedRule = publishRule(ruleName, scalaVersion, ruleImplementation)
    publishedRule.map { dep =>
      val all =
        workspace.listRecursive.filter(file => file.filename.isScala).map {
          file =>
            scalafixProvider
              .runRuleFromDep(
                file,
                ruleName,
                dep,
              )
              .flatMap { edits =>
                val changes = Map(file.toURI.toString -> edits.asJava).asJava
                metalsClient
                  .applyEdit(
                    new ApplyWorkspaceEditParams(
                      new WorkspaceEdit(changes)
                    )
                  )
                  .asScala
              }
        }
      Future.sequence(all.toList).map(_ => ())
    }
  }
}
