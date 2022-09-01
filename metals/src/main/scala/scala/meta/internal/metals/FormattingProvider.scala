package scala.meta.internal.metals

import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Failure
import scala.util.Success

import scala.meta._
import scala.meta.internal.io.FileIO
import scala.meta.internal.metals.Messages.MissingScalafmtConf
import scala.meta.internal.metals.Messages.MissingScalafmtVersion
import scala.meta.internal.metals.Messages.UpdateScalafmtConf
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.semver.SemVer

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import org.eclipse.lsp4j.jsonrpc.CancelChecker
import org.eclipse.{lsp4j => l}
import org.scalafmt.dynamic.ScalafmtDynamicError
import org.scalafmt.interfaces.PositionException
import org.scalafmt.interfaces.Scalafmt
import org.scalafmt.interfaces.ScalafmtReporter

/**
 * Implement text formatting using Scalafmt
 */
final class FormattingProvider(
    workspace: AbsolutePath,
    buffers: Buffers,
    userConfig: () => UserConfiguration,
    client: MetalsLanguageClient,
    clientConfig: ClientConfiguration,
    statusBar: StatusBar,
    icons: Icons,
    tables: Tables,
    buildTargets: BuildTargets,
)(implicit ec: ExecutionContext)
    extends Cancelable {

  import FormattingProvider._

  override def cancel(): Unit = {
    scalafmt.clear()
  }

  private def clearDiagnostics(config: AbsolutePath): Unit = {
    client.publishDiagnostics(
      new l.PublishDiagnosticsParams(
        config.toURI.toString,
        Collections.emptyList(),
      )
    )
  }

  private var scalafmt = FormattingProvider.newScalafmt()
  private val reporterPromise =
    new AtomicReference[Option[Promise[Boolean]]](None)
  private val cancelToken = new AtomicReference[Option[CancelChecker]](None)
  private def isCancelled: Boolean = cancelToken.get().exists(_.isCancelled)
  private def reset(token: CancelChecker): Unit = {
    reporterPromise.get().foreach(_.trySuccess(false))
    reporterPromise.set(None)
    cancelToken.set(Some(token))
  }

  // Warms up the Scalafmt instance so that the first formatting request responds faster.
  // Does nothing if there is no .scalafmt.conf or there is no configured version setting.
  def load(): Unit = {
    if (scalafmtConf.isFile && !Testing.isEnabled) {
      try {
        scalafmt.format(
          scalafmtConf.toNIO,
          Paths.get("Main.scala"),
          "object Main  {}",
        )
      } catch {
        case e: ScalafmtDynamicError =>
          scribe.debug(
            s"Scalafmt issue while warming up due to config issue: ${e.getMessage()}"
          )
      }

    }
  }

  def format(
      path: AbsolutePath,
      token: CancelChecker,
  ): Future[util.List[l.TextEdit]] = {
    scalafmt = scalafmt.withReporter(activeReporter)
    reset(token)
    val input = path.toInputFromBuffers(buffers)
    if (!scalafmtConf.isFile) {
      handleMissingFile(scalafmtConf).map {
        case true =>
          runFormat(path, input).asJava
        case false =>
          Collections.emptyList[l.TextEdit]()
      }
    } else {
      val result = runFormat(path, input)
      if (token.isCancelled) {
        statusBar.addMessage(
          s"${icons.info}Scalafmt cancelled by editor, try saving file again"
        )
      }
      reporterPromise.get() match {
        case Some(promise) =>
          // Wait until "update .scalafmt.conf" dialogue has completed
          // before returning future.
          promise.future.map {
            case true if !token.isCancelled => runFormat(path, input).asJava
            case _ => result.asJava
          }
        case None =>
          Future.successful(result.asJava)
      }
    }
  }

  private def runFormat(path: AbsolutePath, input: Input): List[l.TextEdit] = {
    val fullDocumentRange = Position.Range(input, 0, input.chars.length).toLsp
    val formatted =
      try {
        scalafmt.format(scalafmtConf.toNIO, path.toNIO, input.text)
      } catch {
        case e: ScalafmtDynamicError =>
          scribe.debug(
            s"Skipping Scalafmt due to config issue: ${e.getMessage()}"
          )
          // If we hit this, there is a config issue, and we just return the input.
          // We don't need to worry about handling it here since it's handled by the
          // reporter and showed as an error in the logs anyways.
          input.text
      }
    if (formatted != input.text) {
      List(new l.TextEdit(fullDocumentRange, formatted))
    } else {
      Nil
    }
  }

  private def handleMissingVersion(config: AbsolutePath): Future[Boolean] = {
    askScalafmtVersion().map {
      case Some(version) =>
        val text = config.toInputFromBuffers(buffers).text
        val dialect =
          buildTargets.allScala.map(_.fmtDialect).toList.sorted.lastOption
        val newText =
          ScalafmtConfig.update(text, Some(version), dialect, Map.empty)
        Files.write(config.toNIO, newText.getBytes(StandardCharsets.UTF_8))
        clearDiagnostics(config)
        client.showMessage(
          MissingScalafmtVersion.fixedVersion(isCancelled)
        )
        true
      case None =>
        scribe.info("scalafmt: no version provided for .scalafmt.conf")
        false
    }
  }

  private def askScalafmtVersion(): Future[Option[String]] = {
    if (!tables.dismissedNotifications.ChangeScalafmtVersion.isDismissed) {
      if (clientConfig.isInputBoxEnabled) {
        client
          .metalsInputBox(MissingScalafmtVersion.inputBox())
          .asScala
          .mapOptionInside(_.value)
      } else {
        client
          .showMessageRequest(MissingScalafmtVersion.messageRequest())
          .asScala
          .map { item =>
            if (item == MissingScalafmtVersion.changeVersion) {
              Some(BuildInfo.scalafmtVersion)
            } else if (item == Messages.notNow) {
              tables.dismissedNotifications.ChangeScalafmtVersion
                .dismiss(24, TimeUnit.HOURS)
              None
            } else if (item == Messages.dontShowAgain) {
              tables.dismissedNotifications.ChangeScalafmtVersion
                .dismissForever()
              None
            } else None
          }
      }
    } else Future.successful(None)
  }

  private def handleMissingFile(path: AbsolutePath): Future[Boolean] = {
    if (!tables.dismissedNotifications.CreateScalafmtFile.isDismissed) {
      val params = MissingScalafmtConf.params()
      client.showMessageRequest(params).asScala.map { item =>
        if (item == MissingScalafmtConf.createFile) {
          Files.createDirectories(path.toNIO.getParent)
          Files
            .write(path.toNIO, initialConfig().getBytes(StandardCharsets.UTF_8))
          client.showMessage(MissingScalafmtConf.fixedParams(isCancelled))
          true
        } else if (item == Messages.notNow) {
          tables.dismissedNotifications.CreateScalafmtFile
            .dismiss(24, TimeUnit.HOURS)
          false
        } else if (item == Messages.dontShowAgain) {
          tables.dismissedNotifications.CreateScalafmtFile
            .dismissForever()
          false
        } else false
      }
    } else Future.successful(false)
  }

  private def initialConfig(): String = {
    val version = BuildInfo.scalafmtVersion
    val initialConfig =
      ScalafmtConfig.empty
        .copy(version = Some(SemVer.Version.fromString(version)))

    val versionText =
      s"""|version = "${BuildInfo.scalafmtVersion}"
          |runner.dialect = ${ScalafmtDialect.Scala213.value}""".stripMargin
    inspectDialectRewrite(initialConfig) match {
      case Some(rewrite) => rewrite.rewrite(versionText)
      case None => versionText
    }
  }

  private def inspectDialectRewrite(
      config: ScalafmtConfig
  ): Option[DialectRewrite] = {

    import ScalafmtDialect.ord

    def nonExcludedSources(sourceItem: AbsolutePath): List[AbsolutePath] =
      if (sourceItem.exists)
        FileIO
          .listAllFilesRecursively(sourceItem)
          .filter(f => f.isScalaFilename && !config.isExcluded(f))
          .toList
      else
        List.empty

    def hasCorrectDialectOverride(
        files: List[AbsolutePath],
        dialect: ScalafmtDialect,
    ): Boolean = {
      config.fileOverrides.nonEmpty &&
      files.forall(p => config.overrideFor(p).exists(d => ord.gteq(dialect, d)))
    }

    def inferDialectForSourceItem(
        sourceItem: AbsolutePath,
        buildTargetIds: List[BuildTargetIdentifier],
        default: ScalafmtDialect,
    ): Option[ScalafmtDialect] = {
      if (buildTargetIds.nonEmpty && sourceItem.exists) {
        val required =
          buildTargetIds
            .flatMap(buildTargets.scalaTarget)
            .map(_.fmtDialect)
            .sorted
            .headOption
            .getOrElse(ScalafmtDialect.Scala213)

        if (ord.gt(required, default)) {
          nonExcludedSources(sourceItem) match {
            case Nil => None
            case items if !hasCorrectDialectOverride(items, required) =>
              Some(required)
            case _ => None
          }
        } else None
      } else None
    }

    val default = config.runnerDialect.getOrElse(ScalafmtDialect.Scala213)

    val allTargets = buildTargets.allScala.toList
    val sbtTargetsIds = allTargets.filter(_.isSbt).map(_.info.getId).toSet

    val itemsRequiresUpgrade =
      buildTargets.sourceItemsToBuildTargets.toList.flatMap {
        case (path, ids) if !ids.asScala.exists(sbtTargetsIds.contains(_)) =>
          inferDialectForSourceItem(path, ids.asScala.toList, default)
            .map(d => (path, d))
        case _ => Nil
      }
    if (itemsRequiresUpgrade.nonEmpty) {
      val nonSbtTargets = allTargets.filter(!_.isSbt)
      val minDialect =
        config.runnerDialect match {
          case Some(d) => d
          case None =>
            val allPossibleDialects =
              nonSbtTargets.map(_.fmtDialect)
            if (allPossibleDialects.nonEmpty)
              allPossibleDialects.min
            else
              ScalafmtDialect.Scala213
        }

      val needFileOverride =
        nonSbtTargets.map(_.fmtDialect).distinct.size > 1

      val maxDialect = itemsRequiresUpgrade.map(_._2).max

      val allDirs = itemsRequiresUpgrade.map { case (item, _) =>
        item.toRelative(workspace)
      }

      val fileOverrideDirs = itemsRequiresUpgrade
        .collect {
          case (item, dialect) if dialect != minDialect =>
            item.toRelative(workspace)
        }

      val upgradeType =
        if (needFileOverride && config.fileOverrides.nonEmpty)
          RewriteType.Manual
        else if (needFileOverride)
          RewriteType.FileOverrideDialect
        else
          RewriteType.GlobalDialect

      val out = DialectRewrite(
        allDirs,
        fileOverrideDirs,
        minDialect,
        maxDialect,
        upgradeType,
        config,
      )
      Some(out)
    } else None
  }

  private def checkIfDialectUpgradeRequired(
      config: ScalafmtConfig
  ): Future[Unit] = {
    if (tables.dismissedNotifications.UpdateScalafmtConf.isDismissed)
      Future.unit
    else {
      Future(inspectDialectRewrite(config)).flatMap {
        case Some(rewrite) =>
          val canUpdate = rewrite.canUpdate && scalafmtConf.isInside(workspace)
          val params =
            UpdateScalafmtConf.params(rewrite.maxDialect, canUpdate)

          scribe.info(
            s"Required scalafmt dialect rewrite to '${rewrite.maxDialect.value}'." +
              s" Directories:\n${rewrite.allDirs.mkString("- ", "\n- ", "")}"
          )

          client.showMessageRequest(params).asScala.map { item =>
            if (item == UpdateScalafmtConf.letUpdate) {
              val text = scalafmtConf.toInputFromBuffers(buffers).text
              val updatedText = rewrite.rewrite(text)
              Files.write(
                scalafmtConf.toNIO,
                updatedText.getBytes(StandardCharsets.UTF_8),
              )
            } else if (item == Messages.notNow) {
              tables.dismissedNotifications.UpdateScalafmtConf
                .dismiss(24, TimeUnit.HOURS)
            } else if (item == Messages.dontShowAgain) {
              tables.dismissedNotifications.UpdateScalafmtConf.dismissForever()
            } else ()
          }
        case None => Future.unit
      }
    }
  }

  def validateWorkspace(): Future[Unit] = {
    if (scalafmtConf.exists) {
      val text = scalafmtConf.toInputFromBuffers(buffers).text
      ScalafmtConfig.parse(text) match {
        case Failure(e) =>
          scribe.error(s"Failed to parse ${scalafmtConf}", e)
          Future.unit
        case Success(values) =>
          checkIfDialectUpgradeRequired(values)
      }
    } else {
      Future.unit
    }
  }

  private def scalafmtConf: AbsolutePath = {
    val configpath = userConfig().scalafmtConfigPath
    configpath.getOrElse(workspace.resolve(".scalafmt.conf"))
  }

  private val activeReporter: ScalafmtReporter = new ScalafmtReporter {
    private var downloadingScalafmt = Promise[Unit]()
    override def error(file: Path, message: String): Unit = {
      scribe.error(s"scalafmt: $file: $message")
      if (file == scalafmtConf.toNIO) {
        downloadingScalafmt.trySuccess(())
        if (message.contains("failed to resolve Scalafmt version")) {
          client.showMessage(MissingScalafmtVersion.failedToResolve(message))
        }
        val input = scalafmtConf.toInputFromBuffers(buffers)
        val pos = Position.Range(input, 0, input.chars.length)
        client.publishDiagnostics(
          new l.PublishDiagnosticsParams(
            file.toUri.toString,
            Collections.singletonList(
              new l.Diagnostic(
                new l.Range(
                  new l.Position(0, 0),
                  new l.Position(pos.endLine, pos.endColumn),
                ),
                message,
                l.DiagnosticSeverity.Error,
                "scalafmt",
              )
            ),
          )
        )
      }
    }

    override def error(file: Path, e: Throwable): Unit = {
      downloadingScalafmt.trySuccess(())
      e match {
        case p: PositionException =>
          statusBar.addMessage(
            s"${icons.alert}line ${p.startLine() + 1}: ${p.shortMessage()}"
          )
          scribe.error(s"scalafmt: ${p.longMessage()}")
        case _ =>
          scribe.error(s"scalafmt: $file", e)
      }
    }
    override def excluded(file: Path): Unit = {
      scribe.info(
        s"scalafmt: excluded $file (to format this file, update `project.excludeFilters` in .scalafmt.conf)"
      )
    }

    override def parsedConfig(config: Path, scalafmtVersion: String): Unit = {
      downloadingScalafmt.trySuccess(())
      clearDiagnostics(AbsolutePath(config))
    }

    override def missingVersion(config: Path, defaultVersion: String): Unit = {
      val promise = Promise[Boolean]()
      reporterPromise.set(Some(promise))
      promise.completeWith(handleMissingVersion(AbsolutePath(config)))
      super.missingVersion(config, defaultVersion)
    }

    def downloadOutputStreamWriter(): OutputStreamWriter =
      new OutputStreamWriter(downloadOutputStream())

    def downloadOutputStream(): OutputStream = {
      downloadingScalafmt.trySuccess(())
      downloadingScalafmt = Promise()
      statusBar.trackSlowFuture(
        "Loading Scalafmt",
        downloadingScalafmt.future,
      )
      System.out
    }
    override def downloadWriter(): PrintWriter = {
      new PrintWriter(downloadOutputStream())
    }
  }
}

object FormattingProvider {

  sealed trait RewriteType
  object RewriteType {
    case object Manual extends RewriteType
    sealed trait Auto extends RewriteType
    case object GlobalDialect extends Auto
    case object FileOverrideDialect extends Auto
  }

  case class DialectRewrite(
      allDirs: List[RelativePath],
      fileOverrideDirs: List[RelativePath],
      minDialect: ScalafmtDialect,
      maxDialect: ScalafmtDialect,
      rewriteType: RewriteType,
      config: ScalafmtConfig,
  ) {

    def canUpdate: Boolean =
      rewriteType match {
        case _: RewriteType.Auto => true
        case RewriteType.Manual => false
      }

    def rewrite(text: String): String = {
      val updVersion = versionUpgrade
      rewriteType match {
        case RewriteType.GlobalDialect =>
          ScalafmtConfig.update(
            text,
            version = updVersion,
            runnerDialect = Some(maxDialect),
          )
        case RewriteType.FileOverrideDialect =>
          val fileOverride =
            fileOverrideDirs.map { path =>
              // globs should work with `/` on all platforms
              val unifiedPath =
                if (scala.util.Properties.isWin)
                  path.toString.replace(
                    FileSystems.getDefault.getSeparator,
                    "/",
                  )
                else
                  path.toString

              (s"glob:**/$unifiedPath/**" -> maxDialect)
            }.toMap
          ScalafmtConfig.update(
            text,
            version = updVersion,
            runnerDialect = Some(minDialect),
            fileOverride = fileOverride,
          )
        case RewriteType.Manual => text
      }
    }

    private def versionUpgrade: Option[String] = {
      config.version match {
        case Some(v) if v.major >= 3 => None
        case _ => Some(BuildInfo.scalafmtVersion)
      }
    }

  }

  def newScalafmt(): Scalafmt =
    Scalafmt
      .create(this.getClass.getClassLoader)
      .withReporter(EmptyScalafmtReporter)

}
