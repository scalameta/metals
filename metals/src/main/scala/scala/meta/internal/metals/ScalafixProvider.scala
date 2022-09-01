package scala.meta.internal.metals

import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.{util => ju}

import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Random
import scala.util.Success
import scala.util.Try

import scala.meta._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.mtags.SemanticdbClasspath
import scala.meta.internal.semanticdb.TextDocuments
import scala.meta.io.AbsolutePath

import com.typesafe.config.ConfigFactory
import coursierapi.Dependency
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.{lsp4j => l}
import scalafix.interfaces.Scalafix
import scalafix.interfaces.ScalafixEvaluation
import scalafix.interfaces.ScalafixFileEvaluationError

case class ScalafixProvider(
    buffers: Buffers,
    userConfig: () => UserConfiguration,
    workspace: AbsolutePath,
    statusBar: StatusBar,
    compilations: Compilations,
    languageClient: MetalsLanguageClient,
    buildTargets: BuildTargets,
    buildClient: MetalsBuildClient,
    interactive: InteractiveSemanticdbs,
)(implicit ec: ExecutionContext) {
  import ScalafixProvider._
  private val scalafixCache = TrieMap.empty[ScalaBinaryVersion, Scalafix]
  private val rulesClassloaderCache =
    TrieMap.empty[ScalafixRulesClasspathKey, URLClassLoader]

  // Warms up the Scalafix instance so that the first organize imports request responds faster.
  def load(): Unit = {
    if (!Testing.isEnabled) {
      val tmp = workspace
        .resolve(Directories.tmp)
        .resolve(s"Main${Random.nextLong()}.scala")
      try {
        val targets =
          buildTargets.allScala.toList.groupBy(_.scalaVersion).flatMap {
            case (_, targets) => targets.headOption
          }
        val contents = "object Main{}\n"
        tmp.writeText(contents)
        val testEvaluation =
          for (target <- targets)
            yield scalafixEvaluate(
              tmp,
              target,
              contents,
              produceSemanticdb = true,
              List(organizeImportRuleName),
            )
        val evaluatedCorrectly = testEvaluation.forall {
          case Success(evaluation) => evaluation.isSuccessful()
          case _ => false
        }
        if (!evaluatedCorrectly) scribe.debug("Could not warm up Scalafix")
      } catch {
        case e: Throwable =>
          scribe.debug(
            s"Scalafix issue while warming up due to issue: ${e.getMessage()}",
            e,
          )
      } finally {
        if (tmp.exists) tmp.delete()
      }
    }
  }

  def runAllRules(file: AbsolutePath): Future[List[l.TextEdit]] = {
    val definedRules = rulesFromScalafixConf()
    val result = for {
      buildId <- buildTargets.inverseSources(file)
      target <- buildTargets.scalaTarget(buildId)
    } yield {
      runScalafixRules(
        file,
        target,
        definedRules.toList,
      )
    }
    result.getOrElse(Future.successful(Nil))
  }

  def organizeImports(
      file: AbsolutePath,
      scalaTarget: ScalaTarget,
  ): Future[List[l.TextEdit]] = {
    runScalafixRules(
      file,
      scalaTarget,
      List(organizeImportRuleName),
    )
  }

  def runScalafixRules(
      file: AbsolutePath,
      scalaTarget: ScalaTarget,
      rules: List[String],
      retried: Boolean = false,
  ): Future[List[l.TextEdit]] = {
    val fromDisk = file.toInput
    val inBuffers = file.toInputFromBuffers(buffers)

    compilations.compilationFinished(file).flatMap { _ =>
      val scalafixEvaluation =
        scalafixEvaluate(
          file,
          scalaTarget,
          inBuffers.value,
          retried || isUnsaved(inBuffers.text, fromDisk.text),
          rules,
        )

      scalafixEvaluation match {
        case Failure(exception) =>
          reportScalafixError(
            "Unable to run scalafix, please check logs for more info.",
            exception,
          )
          Future.failed(exception)
        case Success(results)
            if !scalafixSucceded(results) && hasStaleSemanticdb(
              results
            ) && buildClient.buildHasErrors(file) =>
          val msg = "Attempt to organize your imports failed. " +
            "It looks like you have compilation issues causing your semanticdb to be stale. " +
            "Ensure everything is compiling and try again."
          scribe.warn(
            msg
          )
          languageClient.showMessage(
            MessageType.Warning,
            msg,
          )
          Future.successful(Nil)
        case Success(results) if !scalafixSucceded(results) =>
          val scalafixError = getMessageErrorFromScalafix(results)
          val exception = ScalafixRunException(scalafixError)
          if (
            scalafixError.startsWith("Unknown rule") ||
            scalafixError.startsWith("Class not found")
          ) {
            languageClient
              .showMessage(Messages.unknownScalafixRules(scalafixError))
          }

          scribe.error(scalafixError, exception)
          if (!retried && hasStaleSemanticdb(results)) {
            // Retry, since the semanticdb might be stale
            runScalafixRules(file, scalaTarget, rules, retried = true)
          } else {
            Future.failed(exception)
          }
        case Success(results) =>
          Future.successful {
            val edits = for {
              fileEvaluation <- results.getFileEvaluations().headOption
              patches <- fileEvaluation.previewPatches().asScala
            } yield textEditsFrom(patches, inBuffers)
            edits.getOrElse(Nil)
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
   * evaluation also ran succesfully. This ensure that the scalafix run was successful
   * and also that every file evaluation was successful.
   *
   * @param evaluation
   * @return true only if the evaulation for every single file contains no errors
   */
  private def scalafixSucceded(evaluation: ScalafixEvaluation): Boolean =
    evaluation.isSuccessful && evaluation
      .getFileEvaluations()
      .forall(_.isSuccessful)

  private def hasStaleSemanticdb(evaluation: ScalafixEvaluation): Boolean = {
    evaluation
      .getFileEvaluations()
      .headOption
      .flatMap(_.getError().asScala)
      .contains(ScalafixFileEvaluationError.StaleSemanticdbError)
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

  private lazy val scala3DefaultConfig = {
    val path = Files.createTempFile(".scalafix", ".conf")
    AbsolutePath(path).writeText(
      s"""|rules = [
          |  OrganizeImports
          |]
          |OrganizeImports.removeUnused = false
          |
          |""".stripMargin
    )
    path.toFile().deleteOnExit()
    path
  }

  private def scalafixConf(isScala3: Boolean): Option[Path] = {
    val defaultLocation = workspace.resolve(".scalafix.conf")
    val defaultConfig = if (isScala3) Some(scala3DefaultConfig) else None
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
    scalafixConf(isScala3 = false) match {
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
  ): Try[ScalafixEvaluation] = {
    val isScala3 = ScalaVersions.isScala3Version(scalaTarget.scalaVersion)
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

    val scalaVersion = scalaTarget.scalaVersion
    val scalafixRulesKey =
      ScalafixRulesClasspathKey(
        scalaBinaryVersion,
        scalaVersion,
        userConfig(),
        rules,
      )
    // It seems that Scalafix ignores the targetroot parameter and searches the classpath
    // Prepend targetroot to make sure that it's picked up first always
    val classpath =
      (targetRoot.toList ++ scalaTarget.fullClasspath).asJava

    for {
      api <- getScalafix(scalaBinaryVersion)
      urlClassLoaderWithExternalRule <- getRuleClassLoader(
        scalafixRulesKey,
        api.getClass.getClassLoader,
      )
    } yield {
      val scalacOptions = {
        val list = new ju.ArrayList[String](3)

        if (scalaBinaryVersion == "2.13") list.add("-Wunused:imports")
        else list.add("-Ywarn-unused-import")

        if (!isScala3 && scalaTarget.scalac.getOptions().contains("-Xsource:3"))
          list.add("-Xsource:3")

        // We always compile with synthetics:on but scalafix will fail if we don't set it here
        list.add("-P:semanticdb:synthetics:on")
        list
      }

      val evaluated = api
        .newArguments()
        .withScalaVersion(scalaVersion)
        .withClasspath(classpath)
        .withToolClasspath(urlClassLoaderWithExternalRule)
        .withConfig(scalafixConf(isScala3).asJava)
        .withRules(rules.asJava)
        .withPaths(List(diskFilePath.toNIO).asJava)
        .withSourceroot(sourceroot.toNIO)
        .withScalacOptions(scalacOptions)
        .evaluate()

      if (produceSemanticdb)
        targetRoot.foreach(AbsolutePath(_).deleteRecursively())
      evaluated
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
      scalaBinaryVersion: ScalaBinaryVersion
  ): Try[Scalafix] = {
    scalafixCache.get(scalaBinaryVersion) match {
      case Some(value) => Success(value)
      case None =>
        statusBar.trackBlockingTask("Downloading scalafix") {
          val scalafix =
            Try(Scalafix.fetchAndClassloadInstance(scalaBinaryVersion))
          scalafix.foreach(api => scalafixCache.update(scalaBinaryVersion, api))
          scalafix
        }
    }

  }

  private def getRuleClassLoader(
      scalfixRulesKey: ScalafixRulesClasspathKey,
      scalafixClassLoader: ClassLoader,
  ): Try[URLClassLoader] = {
    rulesClassloaderCache.get(scalfixRulesKey) match {
      case Some(value) => Success(value)
      case None =>
        statusBar.trackBlockingTask(
          "Downloading scalafix rules' dependencies"
        ) {
          val rulesDependencies = scalfixRulesKey.usedRulesWithClasspath
          val organizeImportRule =
            Try(
              Embedded.rulesClasspath(rulesDependencies.toList)
            ).map { paths =>
              val classloader = Embedded.toClassLoader(
                Classpath(paths.map(AbsolutePath(_))),
                scalafixClassLoader,
              )
              rulesClassloaderCache.update(scalfixRulesKey, classloader)
              classloader
            }
          organizeImportRule
        }
    }
  }

  private def isUnsaved(fromBuffers: String, fromFile: String): Boolean = {
    // zipAll will extend the shorter collection, which is needed for accurate comparison
    fromBuffers.linesIterator
      .zipAll(fromFile.linesIterator, null, null)
      .exists { case (line1, line2) =>
        line1 != line2
      }
  }

}

object ScalafixProvider {

  type ScalaBinaryVersion = String
  type ScalaVersion = String

  case class ScalafixRulesClasspathKey(
      scalaBinaryVersion: ScalaBinaryVersion,
      usedRulesWithClasspath: Set[Dependency],
  )

  object ScalafixRulesClasspathKey {
    def apply(
        scalaBinaryVersion: String,
        scalaVersion: String,
        userConfig: UserConfiguration,
        rules: List[String],
    ): ScalafixRulesClasspathKey = {
      val rulesClasspath =
        rulesDependencies(scalaVersion, scalaBinaryVersion, userConfig, rules)
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
  ): Set[Dependency] = {
    val fromSettings = userConfig.scalafixRulesDependencies.flatMap {
      dependencyString =>
        Try {
          Dependency.parse(
            dependencyString,
            coursierapi.ScalaVersion.of(scalaVersion),
          )
        } match {
          case Failure(exception) =>
            scribe.warn(s"Could not download `${dependencyString}`", exception)
            None
          case Success(dep) =>
            Some(dep)
        }
    }
    val builtInRuleDeps = builtInRules(scalaBinaryVersion)

    val allDeps = List(
      Dependency.of(
        "com.github.liancheng",
        s"organize-imports_$scalaBinaryVersion",
        BuildInfo.organizeImportVersion,
      )
    ) ++ fromSettings ++ rules.flatMap(builtInRuleDeps.get)
    // only get newest versions for each dependency
    allDeps
      .sortBy(_.getVersion())
      .reverse
      .distinctBy(dep => dep.getModule())
      .toSet
  }

  // Hygiene rules from https://scalacenter.github.io/scalafix/docs/rules/community-rules.html
  private def builtInRules(binaryVersion: String) = {
    val scaluzziDep = Dependency.of(
      "com.github.vovapolu",
      s"scaluzzi_$binaryVersion",
      "latest.release",
    )

    val scalafixUnifiedDep = Dependency.of(
      "com.github.xuwei-k",
      s"scalafix-rules_$binaryVersion",
      "latest.release",
    )

    val scalafixPixivRule = Dependency.of(
      "net.pixiv",
      s"scalafix-pixiv-rule_$binaryVersion",
      "latest.release",
    )

    val depsList = List(
      "EmptyCollectionsUnified" -> Dependency.of(
        "io.github.ghostbuster91.scalafix-unified",
        s"unified_$binaryVersion",
        "latest.release",
      ),
      "UseNamedParameters" -> Dependency.of(
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
