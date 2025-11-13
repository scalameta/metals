package scala.meta.internal.metals

import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.{util => ju}

import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.control.NonFatal

import scala.meta._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.clients.language.MetalsQuickPickItem
import scala.meta.internal.metals.clients.language.MetalsQuickPickParams
import scala.meta.internal.metals.clients.language.MetalsStatusParams
import scala.meta.internal.metals.mcp.ScalafixLlmRuleProvider
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.internal.mtags.SemanticdbClasspath
import scala.meta.internal.semanticdb.TextDocuments
import scala.meta.io.AbsolutePath

import com.typesafe.config.ConfigFactory
import coursier.Dependency
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.{lsp4j => l}
import scalafix.interfaces.Scalafix
import scalafix.interfaces.ScalafixEvaluation
import scalafix.interfaces.ScalafixException
import scalafix.interfaces.ScalafixFileEvaluationError
import scalafix.internal.interfaces.ScalafixCoursier
import scalafix.internal.interfaces.ScalafixInterfacesClassloader

case class ScalafixProvider(
    buffers: Buffers,
    userConfig: () => UserConfiguration,
    workspace: AbsolutePath,
    workDoneProgress: WorkDoneProgress,
    compilations: Compilations,
    languageClient: MetalsLanguageClient,
    buildTargets: BuildTargets,
    interactive: InteractiveSemanticdbs,
    tables: Tables,
    buildHasErrors: AbsolutePath => Boolean,
    statusBar: StatusBar,
)(implicit ec: ExecutionContext, rc: ReportContext) {
  import ScalafixProvider._
  private val scalafixCache = TrieMap.empty[ScalaVersion, Scalafix]
  private val rulesClassloaderCache =
    TrieMap.empty[ScalafixRulesClasspathKey, URLClassLoader]

  def runAllRules(file: AbsolutePath): Future[List[l.TextEdit]] = {
    val definedRules = rulesFromScalafixConf()
    runRules(file, definedRules.toList)
  }

  def runRulesOrPrompt(
      file: AbsolutePath,
      rules: List[String],
  ): Future[List[l.TextEdit]] = {
    lazy val generatedRules =
      ScalafixLlmRuleProvider.generatedRules(workspace).keySet
    lazy val definedRules =
      rulesFromScalafixConf() ++ generatedRules
    val rulesFut =
      if (rules.isEmpty) askForRule(definedRules).map(_.toList)
      else Future.successful(rules)
    rulesFut.flatMap { rulesToRun =>
      def additionalDeps(scalaVersion: ScalaVersion) = {
        val generatedRulesSelected =
          rulesToRun.filter(generatedRules.contains(_))

        ScalafixLlmRuleProvider.additionalDependencies(
          generatedRulesSelected,
          ScalaVersions.scalaBinaryVersionFromFullVersion(scalaVersion),
        )

      }
      runRules(file, rulesToRun, additionalDeps)
    }
  }

  def runRuleFromDep(
      file: AbsolutePath,
      ruleName: String,
      ruleDep: Dependency,
  ): Future[List[l.TextEdit]] = {
    val scalaTarget = buildTargets.inverseSources(file)
    scalaTarget
      .flatMap(buildId => buildTargets.scalaTarget(buildId))
      .map { scalaTarget =>
        val additionalDeps = Map(
          ruleName -> ruleDep
        )
        runScalafixRules(
          file,
          scalaTarget,
          List(ruleName),
          additionalDeps,
        )
      }
      .getOrElse(Future.successful(Nil))
  }

  def organizeImports(
      file: AbsolutePath,
      scalaTarget: ScalaTarget,
      silent: Boolean = false,
  ): Future[List[l.TextEdit]] = {
    runScalafixRules(
      file,
      scalaTarget,
      List(organizeImportRuleName),
      silent = silent,
    )
  }

  def runScalafixRules(
      file: AbsolutePath,
      scalaTarget: ScalaTarget,
      rules: List[String],
      additionalDeps: Map[String, Dependency] = Map.empty,
      retried: Boolean = false,
      silent: Boolean = false,
  ): Future[List[l.TextEdit]] = {
    val fromDisk = file.toInput
    val inBuffers = file.toInputFromBuffers(buffers)

    additionalDeps.foreach { case (ruleName, dep) =>
      scribe.debug(s"Running rule $ruleName with dep $dep")
    }

    compilations
      .compilationFinished(file, compileInverseDependencies = false)
      .flatMap { _ =>
        val scalafixEvaluation =
          scalafixEvaluate(
            file,
            scalaTarget,
            inBuffers.value,
            retried || isUnsaved(inBuffers.text, fromDisk.text),
            rules,
            additionalDeps = additionalDeps,
          )

        scalafixEvaluation
          .recover { case exception =>
            if (!silent) {
              reportScalafixError(
                "Unable to run scalafix, please check logs for more info.",
                exception,
              )
            }
            throw exception
          }
          .flatMap {
            case results
                if !scalafixSucceded(results) && hasStaleOrMissingSemanticdb(
                  results
                ) && buildHasErrors(file) =>
              if (!silent) {
                val statusMsg = "Attempt to organize your imports failed"
                val fullMsg = statusMsg +
                  ". It looks like you have compilation issues causing your semanticdb to be stale. " +
                  "Ensure everything is compiling and try again."
                val params = new MetalsStatusParams(
                  text = statusMsg,
                  level = "warn",
                  show = true,
                  tooltip = fullMsg,
                )
                scribe.warn(
                  fullMsg
                )
                statusBar.addMessage(
                  params
                )
              }
              Future.successful(Nil)
            case results if !scalafixSucceded(results) =>
              val scalafixError = getMessageErrorFromScalafix(results)
              scribe.error(file.toString, scalafixError)
              scribe.error(additionalDeps.toString)
              val exception = ScalafixRunException(scalafixError)
              if (!silent) {
                if (
                  scalafixError.startsWith("Unknown rule") ||
                  scalafixError.startsWith("Class not found")
                ) {
                  languageClient
                    .showMessage(Messages.unknownScalafixRules(scalafixError))
                }
                scribe.error(scalafixError, exception)
              }
              if (!retried && hasStaleOrMissingSemanticdb(results)) {
                // Retry, since the semanticdb might be stale
                runScalafixRules(
                  file,
                  scalaTarget,
                  rules,
                  retried = true,
                  silent = silent,
                )
              } else {
                if (silent) {
                  Future.successful(Nil)
                } else {
                  Future.failed(exception)
                }
              }
            case results =>
              Future.successful {
                val edits = for {
                  fileEvaluation <- results.getFileEvaluations().headOption
                  patches <- fileEvaluation.previewPatches().asScala
                } yield textEditsFrom(patches, inBuffers)
                edits.getOrElse(Nil)
              }

          }
          .recover { case exception =>
            if (silent) {
              Nil
            } else {
              throw exception
            }
          }
      }
  }

  private def createTemporarySemanticdb(
      file: AbsolutePath,
      contents: String,
  ) = {
    interactive
      .textDocument(file, Some(contents))
      .documentIncludingStale
      .flatMap { semanticdb =>
        /* We remove all diagnostics if there is an error so that
         * we don't remove an import by mistake, which just has a typo
         * for example and would produce an unsued warning.
         * Without additional diagnotics imports will only get rearranged.
         */
        val toSave =
          if (semanticdb.diagnostics.exists(_.severity.isError))
            semanticdb.withDiagnostics(Seq.empty)
          else
            semanticdb
        val dir = workspace.resolve(Directories.tmp)
        file.toRelativeInside(workspace).flatMap { relativePath =>
          val writeTo =
            dir.resolve(SemanticdbClasspath.fromScalaOrJava(relativePath))
          writeTo.parent.createDirectories()
          val docs = TextDocuments(Seq(toSave))
          Files.write(writeTo.toNIO, docs.toByteArray)
          Option(dir.toNIO)
        }
      }

  }

  /**
   * Scalafix may be ran successfully, but that doesn't mean that every file
   * evaluation also ran successfully. This ensure that the scalafix run was successful
   * and also that every file evaluation was successful.
   *
   * @param evaluation
   * @return true only if the evaluation for every single file contains no errors
   */
  private def scalafixSucceded(evaluation: ScalafixEvaluation): Boolean =
    evaluation.isSuccessful && evaluation
      .getFileEvaluations()
      .forall(_.isSuccessful)

  private def hasStaleOrMissingSemanticdb(
      evaluation: ScalafixEvaluation
  ): Boolean = {
    val error = evaluation
      .getFileEvaluations()
      .headOption
      .flatMap(_.getError().asScala)
    error.contains(ScalafixFileEvaluationError.StaleSemanticdbError) || error
      .contains(ScalafixFileEvaluationError.MissingSemanticdbError)
  }

  /**
   * Assumes that [[ScalafixProvider.scalafixSucceded]] has been called and
   * returned false
   *
   * @param evaluation
   * @return the error message of the evaluation or file evaluation
   */
  private def getMessageErrorFromScalafix(
      evaluation: ScalafixEvaluation
  ): String = {
    (if (!evaluation.isSuccessful())
       evaluation.getErrorMessage().asScala
     else
       evaluation
         .getFileEvaluations()
         .headOption
         .flatMap(_.getErrorMessage().asScala))
      .getOrElse("Unexpected error while running Scalafix.")
  }

  private lazy val scala3RemoveUnusedDefaultConfig = {
    val path = Files.createTempFile(".scalafix", ".conf")
    AbsolutePath(path).writeText(
      s"""|rules = [
          |  OrganizeImports
          |]
          |OrganizeImports.removeUnused = true
          |OrganizeImports.targetDialect = Scala3
          |
          |ExplicitResultTypes.fetchScala3CompilerArtifactsOnVersionMismatch = true
          |
          |""".stripMargin
    )
    path.toFile().deleteOnExit()
    path
  }

  private lazy val scala3DefaultConfig = {
    val path = Files.createTempFile(".scalafix", ".conf")
    AbsolutePath(path).writeText(
      s"""|rules = [
          |  OrganizeImports
          |]
          |OrganizeImports.removeUnused = false
          |OrganizeImports.targetDialect = Scala3
          |
          |ExplicitResultTypes.fetchScala3CompilerArtifactsOnVersionMismatch = true
          |
          |""".stripMargin
    )
    path.toFile().deleteOnExit()
    path
  }

  private def scalafixConf(
      isScala3Dialect: Boolean,
      canRemoveUnused: Boolean,
  ): Option[Path] = {
    val defaultLocation = workspace.resolve(".scalafix.conf")
    val defaultConfig =
      if (isScala3Dialect) {
        if (canRemoveUnused) Some(scala3RemoveUnusedDefaultConfig)
        else Some(scala3DefaultConfig)
      } else None
    userConfig().scalafixConfigPath match {
      case Some(path) if !path.isFile && defaultLocation.isFile =>
        languageClient.showMessage(
          MessageType.Warning,
          s"No configuration at $path, using default at $defaultLocation.",
        )
        Some(defaultLocation.toNIO)
      case Some(path) if !path.isFile =>
        languageClient.showMessage(
          MessageType.Warning,
          s"No configuration at $path, using Scalafix defaults.",
        )
        defaultConfig
      case Some(path) => Some(path.toNIO)
      case None if defaultLocation.isFile =>
        Some(defaultLocation.toNIO)
      case _ => defaultConfig
    }
  }

  private def rulesFromScalafixConf(): Set[String] = {
    scalafixConf(isScala3Dialect = false, canRemoveUnused = false) match {
      case None => Set.empty
      case Some(configPath) =>
        val conf = ConfigFactory.parseFile(configPath.toFile)
        if (conf.hasPath("rules"))
          conf
            .getList("rules")
            .map { item =>
              item.unwrapped().toString()
            }
            .asScala
            .toSet
        else Set.empty
    }

  }

  /**
   * Tries to use the Scalafix rule to organize imports.
   *
   * @param file file to run the rule on
   * @param scalaTarget target with all the data about the module
   * @param inBuffers file version that might not be saved to disk
   * @param produceSemanticdb when set to true, we will try to create semanticdb and
   * @param rules list of rules to execute
   * @param suggestConfigAmend if should suggest updating scalafix configuration where relevant
   * save to disk for Scalafix to use. This make organize imports work even if the file is
   * unsaved. This however requires us to save both the file and semanticdb.
   * @return
   */
  private def scalafixEvaluate(
      file: AbsolutePath,
      scalaTarget: ScalaTarget,
      inBuffers: String,
      produceSemanticdb: Boolean,
      rules: List[String],
      suggestConfigAmend: Boolean = true,
      shouldRetry: Boolean = true,
      additionalDeps: Map[String, Dependency] = Map.empty,
  ): Future[ScalafixEvaluation] = {
    val isScala3 = ScalaVersions.isScala3Version(scalaTarget.scalaVersion)
    val isSource3 = scalaTarget.scalac.getOptions().contains("-Xsource:3")

    val scalaBinaryVersion =
      if (isScala3) "2.13" else scalaTarget.scalaBinaryVersion
    val targetRoot =
      if (produceSemanticdb) createTemporarySemanticdb(file, inBuffers)
      else
        Some(scalaTarget.targetroot.toNIO)

    val sourceroot =
      if (produceSemanticdb)
        targetRoot.map(AbsolutePath(_)).getOrElse(workspace)
      else workspace

    val diskFilePath = if (produceSemanticdb) {
      file
        .toRelativeInside(workspace)
        .map { relativePath =>
          val tempFilePath = sourceroot.resolve(relativePath)
          tempFilePath.writeText(inBuffers)
          tempFilePath
        }
        .getOrElse(file)
    } else {
      file
    }

    val scalaVersion = ScalaVersions.dropVendorSuffix(scalaTarget.scalaVersion)
    val scalafixRulesKey =
      ScalafixRulesClasspathKey(
        scalaBinaryVersion,
        scalaVersion,
        userConfig(),
        rules,
        additionalDeps,
      )
    // It seems that Scalafix ignores the targetroot parameter and searches the classpath
    // Prepend targetroot to make sure that it's picked up first always
    val lazyClasspath = buildTargets
      .fullClasspath(scalaTarget.id, Promise[Unit]())
      .getOrElse(Future.successful(Nil))
      .map { classpath =>
        (targetRoot.toList ++ classpath.map(_.toNIO)).asJava
      }

    val result = for {
      api <- getScalafix(scalaTarget.scalaVersion)
      urlClassLoaderWithExternalRule <- getRuleClassLoader(
        scalafixRulesKey,
        api.getClass.getClassLoader,
      )
    } yield {
      val scalacOptions = {
        val list = new ju.ArrayList[String](3)

        scalaBinaryVersion match {
          case "3" => list.add("-Wunused:import")
          case "2.13" => list.add("-Wunused:imports")
          case _ => list.add("-Ywarn-unused-import")
        }

        if (!isScala3 && isSource3)
          list.add("-Xsource:3")

        // We always compile with synthetics:on but scalafix will fail if we don't set it here
        list.add("-P:semanticdb:synthetics:on")
        list
      }

      val evalFuture = for {
        classpath <- lazyClasspath
        confFile <- getScalafixConf(
          isSource3,
          rules,
          scalaVersion,
          suggestConfigAmend,
        )
      } yield {
        val evaluated = api
          .newArguments()
          .withScalaVersion(scalaVersion)
          .withClasspath(classpath)
          .withToolClasspath(urlClassLoaderWithExternalRule)
          .withConfig(confFile.asJava)
          .withRules(rules.asJava)
          .withPaths(List(diskFilePath.toNIO).asJava)
          .withSourceroot(sourceroot.toNIO)
          .withScalacOptions(scalacOptions)
          .evaluate()

        if (produceSemanticdb) {
          // Clean up created file and semanticdbs from `.metals/.tmp` directory
          targetRoot.foreach { root =>
            try {
              if (diskFilePath.toNIO.startsWith(root))
                diskFilePath.deleteWithEmptyParents()
              AbsolutePath(root.resolve("META-INF")).deleteRecursively()
            } catch {
              case NonFatal(e) =>
                scribe.error(
                  s"Failed to clean up temporary files after scalafix evaluation: $e"
                )
            }
          }
        }
        evaluated
      }
      evalFuture.recoverWith {
        case serviceError: java.util.ServiceConfigurationError =>
          scribe.error(
            "Scalafix classloading error, retrying with new classloader",
            serviceError,
          )
          val classpath =
            urlClassLoaderWithExternalRule.getURLs().mkString("\n")
          val report =
            Report(
              "scalafix-classloading-error",
              s"""|Could not load scalafix rules.
                  |
                  |classpath:
                  |${classpath}
                  |
                  |file: $file
                  |
                  |scalaVersion: ${scalaTarget.scalaVersion}
                  |
                  |""".stripMargin,
              serviceError,
            )
          rc.incognito().create(() => report)
          if (shouldRetry) {
            rulesClassloaderCache.remove(scalafixRulesKey)
            scalafixEvaluate(
              file,
              scalaTarget,
              inBuffers,
              produceSemanticdb,
              rules,
              suggestConfigAmend,
              shouldRetry = false,
            )
          } else Future.failed(serviceError)
      }
    }
    result.flatten
  }

  private def getScalafixConf(
      isScalaSource: Boolean,
      rules: List[String],
      scalaVersion: String,
      suggestConfigAmend: Boolean,
  ): Future[Option[Path]] = {
    val isScala3 = scalaVersion.startsWith("3")
    val isScala3Dialect = isScala3 || isScalaSource
    val canRemoveUnused = !isScala3 ||
      // https://github.com/scala/scala3/pull/17835
      // https://github.com/scala/scala3/pull/20315
      Seq("3.0", "3.1", "3.2", "3.3.0", "3.3.1", "3.3.2", "3.3.3")
        .forall(v => !scalaVersion.startsWith(v))
    val confFile = scalafixConf(isScala3Dialect, canRemoveUnused)
    confFile match {
      case Some(path)
          if isScala3Dialect && suggestConfigAmend && rules.contains(
            organizeImportRuleName
          ) && !tables.dismissedNotifications.ScalafixConfAmend.isDismissed =>
        val removeUnusedSetting =
          if (canRemoveUnused) Nil
          else List(("OrganizeImports.removeUnused", "false"))
        val amendSettings =
          ("OrganizeImports.targetDialect", "Scala3") :: removeUnusedSetting
        val scalaconfFileText =
          try (Some(Files.readString(path)))
          catch {
            case NonFatal(e) =>
              scribe.warn(
                s"Failed to read in `.scalafix.conf` with an error $e"
              )
              None
          }
        (for {
          text <- scalaconfFileText
          config = ConfigFactory.parseString(text)
          newSettings = amendSettings.filterNot { case (name, _) =>
            config.hasPath(name)
          }
          if !newSettings.isEmpty
        } yield {
          val settingLines = newSettings.map { case (name, value) =>
            s"$name = $value"
          }
          languageClient
            .showMessageRequest(
              Messages.ScalafixConfig
                .amendRequest(settingLines, scalaVersion, isScalaSource)
            )
            .asScala
            .map {
              case Messages.ScalafixConfig.adjustScalafix =>
                try {
                  Files.write(
                    path,
                    settingLines
                      .mkString(System.lineSeparator)
                      .getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.APPEND,
                  )
                } catch {
                  case NonFatal(e) =>
                    scribe.warn(s"Failed to amend scalafix config: $e")
                }
              case Messages.ScalafixConfig.dontShowAgain =>
                tables.dismissedNotifications.ScalafixConfAmend.dismissForever()
              case _ =>
            }
            .withTimeout(15, ju.concurrent.TimeUnit.SECONDS)
            .map(_ => confFile)
        }).getOrElse(Future.successful(confFile))
      case _ => Future.successful(confFile)
    }
  }

  private def reportScalafixError(
      message: String,
      exception: Throwable,
  ): Unit = {
    val params = new MessageParams(MessageType.Error, message)
    scribe.error(message, exception)
    languageClient.showMessage(params)
  }

  private def textEditsFrom(
      newFileContent: String,
      input: Input,
  ): List[l.TextEdit] = {
    val fullDocumentRange = Position.Range(input, 0, input.chars.length).toLsp
    if (newFileContent != input.text) {
      List(new l.TextEdit(fullDocumentRange, newFileContent))
    } else {
      Nil
    }
  }

  private def getScalafix(
      scalaVersion: ScalaVersion
  ): Future[Scalafix] = Future {
    scalafixCache.getOrElseUpdate(
      scalaVersion, {
        workDoneProgress.trackBlocking("Downloading scalafix") {
          val scalafix =
            if (scalaVersion.startsWith("2.11")) scala211Fallback
            else
              try {
                Scalafix.fetchAndClassloadInstance(scalaVersion)
              } catch {
                case e: ScalafixException
                    if e.getMessage().contains("Failed to fetch") =>
                  Embedded.downloadScalafix(scalaVersion)
                  Scalafix.fetchAndClassloadInstance(scalaVersion)
              }
          scalafix
        }
      },
    )

  }

  private def scala211Fallback: Scalafix = {
    // last version that supports Scala 2.11.12
    val latestSupporting = "0.10.4"
    val jars = ScalafixCoursier.scalafixCliJars(
      Embedded.apiRepositories.asJava,
      latestSupporting,
      V.scala211,
    )
    val parent = new ScalafixInterfacesClassloader(
      classOf[Scalafix].getClassLoader()
    );
    Scalafix.classloadInstance(
      new URLClassLoader(jars.asScala.toArray, parent)
    );
  }

  private def getRuleClassLoader(
      scalfixRulesKey: ScalafixRulesClasspathKey,
      scalafixClassLoader: ClassLoader,
  ): Future[URLClassLoader] = Future {
    rulesClassloaderCache.getOrElseUpdate(
      scalfixRulesKey, {
        workDoneProgress.trackBlocking(
          "Downloading scalafix rules' dependencies"
        ) {
          val rulesDependencies = scalfixRulesKey.usedRulesWithClasspath
          val organizeImportRule =
            // Scalafix version that supports Scala 2.11 doesn't have the rule built in
            if (scalfixRulesKey.scalaVersion.startsWith("2.11"))
              Some(
                Embedded.dependencyOf(
                  "com.github.liancheng",
                  "organize-imports_2.11",
                  "0.6.0",
                )
              )
            else None

          val paths =
            Embedded.rulesClasspath(
              rulesDependencies.toList ++ organizeImportRule
            )
          val classloader = Embedded.toClassLoader(
            Classpath(paths.map(AbsolutePath(_))),
            scalafixClassLoader,
          )
          classloader
        }
      },
    )
  }

  private def isUnsaved(fromBuffers: String, fromFile: String): Boolean = {
    // zipAll will extend the shorter collection, which is needed for accurate comparison
    fromBuffers.linesIterator
      .zipAll(fromFile.linesIterator, null, null)
      .exists { case (line1, line2) =>
        line1 != line2
      }
  }

  private def askForRule(rules: Set[String]): Future[Option[String]] =
    languageClient
      .metalsQuickPick(
        MetalsQuickPickParams(
          items = rules.toList.map(r => MetalsQuickPickItem(r, r)).asJava,
          placeHolder = "Rule",
        )
      )
      .asScala
      .map(resultOpt => resultOpt.map(_.itemId))

  private def runRules(
      file: AbsolutePath,
      rules: List[String],
      additionalRules: (ScalaVersion) => Map[String, Dependency] = _ =>
        Map.empty,
  ): Future[List[l.TextEdit]] = {
    val result = for {
      buildId <- buildTargets.inverseSources(file)
      target <- buildTargets.scalaTarget(buildId)
    } yield {
      runScalafixRules(
        file,
        target,
        rules,
        additionalRules(target.scalaVersion),
      )
    }
    result.getOrElse(Future.successful(Nil))
  }
}

object ScalafixProvider {

  type ScalaVersion = String

  case class ScalafixRulesClasspathKey(
      scalaVersion: ScalaVersion,
      usedRulesWithClasspath: Set[Dependency],
  )

  object ScalafixRulesClasspathKey {
    def apply(
        scalaBinaryVersion: String,
        scalaVersion: String,
        userConfig: UserConfiguration,
        rules: List[String],
        additionalDeps: Map[String, Dependency],
    ): ScalafixRulesClasspathKey = {
      val rulesClasspath =
        rulesDependencies(
          scalaVersion,
          scalaBinaryVersion,
          userConfig,
          rules,
          additionalDeps,
        )
      ScalafixRulesClasspathKey(scalaBinaryVersion, rulesClasspath)
    }
  }
  case class ScalafixRunException(msg: String) extends Exception(msg)

  val organizeImportRuleName = "OrganizeImports"

  def rulesDependencies(
      scalaVersion: String,
      scalaBinaryVersion: String,
      userConfig: UserConfiguration,
      rules: List[String],
      additionalDeps: Map[String, Dependency],
  ): Set[Dependency] = {
    val fromSettings: Seq[Dependency] =
      userConfig.scalafixRulesDependencies.flatMap { dependencyString =>
        Try {
          val classic = coursierapi.Dependency.parse(
            dependencyString,
            coursierapi.ScalaVersion.of(scalaVersion),
          )
          Embedded.dependencyOf(
            classic.getModule().getOrganization(),
            classic.getModule().getName(),
            classic.getVersion(),
          )
        } match {
          case Failure(exception) =>
            scribe.warn(s"Could not download `${dependencyString}`", exception)
            None
          case Success(dep) =>
            Some(dep)
        }
      }
    val builtInRuleDeps = builtInRules(scalaBinaryVersion) ++ additionalDeps

    val allDeps = fromSettings ++ rules.flatMap(builtInRuleDeps.get)
    // only get newest versions for each dependency
    allDeps
      .sortBy(_.versionConstraint.asString)
      .reverse
      .distinctBy(dep => dep.module)
      .toSet
  }

  // Hygiene rules from https://scalacenter.github.io/scalafix/docs/rules/community-rules.html
  private def builtInRules(binaryVersion: String) = {
    val scaluzziDep = Embedded.dependencyOf(
      "com.github.vovapolu",
      s"scaluzzi_$binaryVersion",
      "latest.release",
    )

    val scalafixUnifiedDep = Embedded.dependencyOf(
      "com.github.xuwei-k",
      s"scalafix-rules_$binaryVersion",
      "latest.release",
    )

    val scalafixPixivRule = Embedded.dependencyOf(
      "net.pixiv",
      s"scalafix-pixiv-rule_$binaryVersion",
      "latest.release",
    )

    val depsList = List(
      "EmptyCollectionsUnified" -> Embedded.dependencyOf(
        "io.github.ghostbuster91.scalafix-unified",
        s"unified_$binaryVersion",
        "latest.release",
      ),
      "UseNamedParameters" -> Embedded.dependencyOf(
        "com.github.jatcwang",
        s"scalafix-named-params_$binaryVersion",
        "latest.release",
      ),
      "MissingFinal" -> scaluzziDep,
      "Disable" -> scaluzziDep,
      "AddExplicitImplicitTypes" -> scalafixUnifiedDep,
      "AddLambdaParamParentheses" -> scalafixUnifiedDep,
      "CirceCodec" -> scalafixUnifiedDep,
      "DirectoryAndPackageName" -> scalafixUnifiedDep,
      "DuplicateWildcardImport" -> scalafixUnifiedDep,
      "ExplicitImplicitTypes" -> scalafixUnifiedDep,
      "FileNameConsistent" -> scalafixUnifiedDep,
      "ImplicitValueClass" -> scalafixUnifiedDep,
      "KindProjector" -> scalafixUnifiedDep,
      "LambdaParamParentheses" -> scalafixUnifiedDep,
      "NoElse" -> scalafixUnifiedDep,
      "ObjectSelfType" -> scalafixUnifiedDep,
      "RemoveEmptyObject" -> scalafixUnifiedDep,
      "RemovePureEff" -> scalafixUnifiedDep,
      "RemoveSamePackageImport" -> scalafixUnifiedDep,
      "ReplaceSymbolLiterals" -> scalafixUnifiedDep,
      "Scala3ImportRewrite" -> scalafixUnifiedDep,
      "Scala3ImportWarn" -> scalafixUnifiedDep,
      "Scala3Placeholder" -> scalafixUnifiedDep,
      "ScalaApp" -> scalafixUnifiedDep,
      "ScalazEitherInfix" -> scalafixUnifiedDep,
      "SimplifyForYield" -> scalafixUnifiedDep,
      "ThrowableToNonFatal" -> scalafixUnifiedDep,
      "UnnecessaryCase" -> scalafixUnifiedDep,
      "UnnecessaryMatch" -> scalafixUnifiedDep,
      "UnnecessarySort" -> scalafixUnifiedDep,
      "UnnecessarySortRewriteConfig" -> scalafixUnifiedDep,
      "UnnecessarySortRewrite" -> scalafixUnifiedDep,
      "UnusedConstructorParams" -> scalafixUnifiedDep,
      "UnusedTypeParams" -> scalafixUnifiedDep,
      "UnnecessarySemicolon" -> scalafixPixivRule,
      "ZeroIndexToHead" -> scalafixPixivRule,
      "CheckIsEmpty" -> scalafixPixivRule,
      "NonCaseException" -> scalafixPixivRule,
      "UnifyEmptyList" -> scalafixPixivRule,
      "SingleConditionMatch" -> scalafixPixivRule,
    )
    val depsMaps = depsList.toMap
    // make sure there are no duplicate rules
    assert(depsMaps.size == depsList.size, "Found duplicate scalafix rules")
    depsMaps
  }

}
