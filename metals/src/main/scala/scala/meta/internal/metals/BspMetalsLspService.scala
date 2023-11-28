package scala.meta.internal.metals

import java.nio.file._
import java.util
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import scala.collection.immutable.Nil
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.TimeoutException
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.control.NonFatal

import scala.meta.internal.bsp.BspConfigGenerationStatus._
import scala.meta.internal.bsp.BspConfigGenerator
import scala.meta.internal.bsp.BspConnector
import scala.meta.internal.bsp.BspServers
import scala.meta.internal.bsp.BspSession
import scala.meta.internal.bsp.BuildChange
import scala.meta.internal.bsp.ConnectionBspStatus
import scala.meta.internal.bsp.ScalaCliBspScope
import scala.meta.internal.builds.BloopInstall
import scala.meta.internal.builds.BspErrorHandler
import scala.meta.internal.builds.BuildServerProvider
import scala.meta.internal.builds.BuildTool
import scala.meta.internal.builds.BuildToolSelector
import scala.meta.internal.builds.BuildTools
import scala.meta.internal.builds.SbtBuildTool
import scala.meta.internal.builds.ScalaCliBuildTool
import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.builds.ShowBspErrorHandler
import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.Messages.IncompatibleBloopVersion
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ammonite.Ammonite
import scala.meta.internal.metals.clients.language.ConfiguredLanguageClient
import scala.meta.internal.metals.doctor.Doctor
import scala.meta.internal.metals.doctor.HeadDoctor
import scala.meta.internal.metals.formatting.OnTypeFormattingProvider
import scala.meta.internal.metals.formatting.RangeFormattingProvider
import scala.meta.internal.metals.watcher.FileWatcherEvent
import scala.meta.internal.metals.watcher.FileWatcherEvent.EventType
import scala.meta.internal.semver.SemVer
import scala.meta.io.AbsolutePath

import ch.epfl.scala.{bsp4j => b}
import org.eclipse.lsp4j._

class BspMetalsLspService(
    ec: ExecutionContextExecutorService,
    sh: ScheduledExecutorService,
    serverInputs: MetalsServerInputs,
    languageClient: ConfiguredLanguageClient,
    initializeParams: InitializeParams,
    clientConfig: ClientConfiguration,
    statusBar: StatusBar,
    focusedDocument: () => Option[AbsolutePath],
    shellRunner: ShellRunner,
    timerProvider: TimerProvider,
    initTreeView: () => Unit,
    folder: AbsolutePath,
    folderVisibleName: Option[String],
    headDoctor: HeadDoctor,
    bspStatus: BspStatus,
) extends MetalsLspService(
      ec,
      sh,
      serverInputs,
      languageClient,
      initializeParams,
      clientConfig,
      statusBar,
      focusedDocument,
      shellRunner,
      timerProvider,
      initTreeView,
      folder,
      folderVisibleName,
      headDoctor,
      maxScalaCliServers = 3,
    ) {
  import serverInputs._

  override def cancel(): Unit = {
    if (isCancelled.compareAndSet(false, true)) {
      val buildShutdown = bspSession match {
        case Some(session) => session.shutdown()
        case None => Future.successful(())
      }
      try {
        cancelables.cancel()
      } catch {
        case NonFatal(_) =>
      }
      try buildShutdown.asJava.get(100, TimeUnit.MILLISECONDS)
      catch {
        case _: TimeoutException =>
      }
    }
  }

  val buildTools: BuildTools = new BuildTools(
    folder,
    bspGlobalDirectories,
    () => userConfig,
    () => tables.buildServers.selectedServer().nonEmpty,
  )

  override protected def optBuildTools: Option[BuildTools] = Some(buildTools)

  var currentBspSession: Option[BspSession] = Option.empty[BspSession]
  override def bspSession = currentBspSession
  val isImportInProcess = new AtomicBoolean(false)
  var buildServerPromise: Promise[Unit] = Promise[Unit]()

  override protected def indexerBuildTools: Seq[Indexer.BuildTool] =
    Seq(
      Indexer.BuildTool(
        "main",
        mainBuildTargetsData,
        ImportedBuild.fromList(
          bspSession.map(_.lastImportedBuild).getOrElse(Nil)
        ),
      ),
      Indexer.BuildTool(
        "ammonite",
        ammonite.buildTargetsData,
        ammonite.lastImportedBuild,
      ),
    ) ++ super.indexerBuildTools

  private val onBuildChanged =
    BatchedFunction.fromFuture[AbsolutePath, BuildChange](
      onBuildChangedUnbatched,
      "onBuildChanged",
    )

  override val pauseables: Pauseable = Pauseable.fromPausables(
    onBuildChanged ::
      parseTrees ::
      compilations.pauseables
  )

  private val onTypeFormattingProvider =
    new OnTypeFormattingProvider(buffers, trees, () => userConfig)
  private val rangeFormattingProvider =
    new RangeFormattingProvider(buffers, trees, () => userConfig)

  private val bloopInstall: BloopInstall = new BloopInstall(
    folder,
    languageClient,
    buildTools,
    tables,
    shellRunner,
    () => userConfig,
  )

  private val bspConfigGenerator: BspConfigGenerator = new BspConfigGenerator(
    folder,
    languageClient,
    shellRunner,
    statusBar,
    () => userConfig,
  )

  private val connectionBspStatus =
    new ConnectionBspStatus(bspStatus, folder, clientConfig.icons())

  override protected val bspErrorHandler: BspErrorHandler =
    new ShowBspErrorHandler(
      () => bspSession,
      tables,
      connectionBspStatus,
    )

  private val bloopServers: BloopServers = new BloopServers(
    buildClient,
    languageClient,
    tables,
    clientConfig.initialConfig,
  )

  private val bspServers: BspServers = new BspServers(
    folder,
    charset,
    languageClient,
    buildClient,
    tables,
    bspGlobalDirectories,
    clientConfig.initialConfig,
    () => userConfig,
  )

  private val bspConnector: BspConnector = new BspConnector(
    bloopServers,
    bspServers,
    buildTools,
    languageClient,
    tables,
    () => userConfig,
    statusBar,
    bspConfigGenerator,
    () => bspSession.map(_.mainConnection),
    restartBspServer,
    connectionBspStatus,
  )

  private val formattingProvider: FormattingProvider = new FormattingProvider(
    folder,
    buffers,
    () => userConfig,
    languageClient,
    clientConfig,
    statusBar,
    clientConfig.icons,
    tables,
    buildTargets,
  )

  private val ammonite: Ammonite = register {
    val amm = new Ammonite(
      buffers,
      compilers,
      compilations,
      statusBar,
      diagnostics,
      tables,
      languageClient,
      buildClient,
      () => userConfig,
      () => indexer.profiledIndexWorkspace(() => ()),
      () => folder,
      focusedDocument,
      clientConfig.initialConfig,
      scalaVersionSelector,
      parseTreesAndPublishDiags,
    )
    buildTargets.addData(amm.buildTargetsData)
    amm
  }

  protected def optFormattingProvider: Option[FormattingProvider] = Some(
    formattingProvider
  )

  private val javaFormattingProvider: JavaFormattingProvider =
    new JavaFormattingProvider(
      buffers,
      () => userConfig,
      buildTargets,
    )

  override val doctor: Doctor = new Doctor(
    folder,
    buildTargets,
    diagnostics,
    languageClient,
    () => bspSession,
    () => Some(bspConnector.resolve()),
    tables,
    clientConfig,
    mtagsResolver,
    () => userConfig.javaHome,
    maybeJdkVersion,
    getVisibleName,
    Some(buildTools),
    Some(connectionBspStatus),
  )

  val gitHubIssueFolderInfo = new GitHubIssueFolderInfo(
    () => tables.buildTool.selectedBuildTool(),
    buildTargets,
    () => bspSession,
    () => bspConnector.resolve(),
    buildTools,
  )

  private val buildToolSelector: BuildToolSelector = new BuildToolSelector(
    languageClient,
    tables,
  )

  private val popupChoiceReset: PopupChoiceReset = new PopupChoiceReset(
    folder,
    tables,
    languageClient,
    headDoctor.executeRefreshDoctor,
    () => slowConnectToBuildServer(forceImport = true),
    bspConnector,
    () => quickConnectToBuildServer(),
  )

  protected def loadFingerPrints(): Unit = {
    // load fingerprints from last execution
    fingerprints.addAll(tables.fingerprints.load())
  }

  protected def registerNiceToHaveFilePatterns(): Unit = {
    for {
      params <- Option(initializeParams)
      capabilities <- Option(params.getCapabilities)
      workspace <- Option(capabilities.getWorkspace)
      didChangeWatchedFiles <- Option(workspace.getDidChangeWatchedFiles)
      if didChangeWatchedFiles.getDynamicRegistration
    } yield {
      languageClient.registerCapability(
        new RegistrationParams(
          List(
            new Registration(
              "1",
              "workspace/didChangeWatchedFiles",
              clientConfig.globSyntax.registrationOptions(
                this.folder
              ),
            )
          ).asJava
        )
      )
    }
  }

  override def initialized(): Future[Unit] = {
    loadFingerPrints()
    registerNiceToHaveFilePatterns()
    tables.connect()

    for {
      _ <- maybeSetupScalaCli()
      _ <-
        Future
          .sequence(
            List[Future[Unit]](
              Future(buildTools.initialize()),
              quickConnectToBuildServer().ignoreValue,
              slowConnectToBuildServer(forceImport = false).ignoreValue,
              Future(workspaceSymbols.indexClasspath()),
              Future(formattingProvider.load()),
            )
          )
    } yield ()
  }

  override def onShutdown(): Unit = {
    tables.fingerprints.save(fingerprints.getAllFingerprints())
    cancel()
  }

  override def onUserConfigUpdate(
      newConfig: UserConfiguration
  ): Future[Unit] = {
    val old = userConfig
    val baseOnChange = super.onUserConfigUpdate(newConfig)

    val slowConnect =
      if (userConfig.customProjectRoot != old.customProjectRoot) {
        tables.buildTool.reset()
        tables.buildServers.reset()
        slowConnectToBuildServer(false).ignoreValue
      } else Future.successful(())

    val restartBuildServer = bspSession
      .map { session =>
        if (session.main.isBloop) {
          bloopServers
            .ensureDesiredVersion(
              userConfig.currentBloopVersion,
              session.version,
              userConfig.bloopVersion.nonEmpty,
              old.bloopVersion.isDefined,
              () => autoConnectToBuildServer,
            )
            .flatMap { _ =>
              bloopServers.ensureDesiredJvmSettings(
                userConfig.bloopJvmProperties,
                userConfig.javaHome,
                () => autoConnectToBuildServer(),
              )
            }
        } else if (
          userConfig.ammoniteJvmProperties != old.ammoniteJvmProperties && buildTargets.allBuildTargetIds
            .exists(Ammonite.isAmmBuildTarget)
        ) {
          languageClient
            .showMessageRequest(Messages.AmmoniteJvmParametersChange.params())
            .asScala
            .flatMap {
              case item
                  if item == Messages.AmmoniteJvmParametersChange.restart =>
                ammonite.reload()
              case _ =>
                Future.successful(())
            }
        } else {
          Future.successful(())
        }
      }
      .getOrElse(Future.successful(()))

    for {
      _ <- slowConnect
      _ <- Future.sequence(List(restartBuildServer, baseOnChange))
    } yield ()
  }

  override protected def maybeAmendScalaCliBspConfig(
      file: AbsolutePath
  ): Future[Unit] = {
    def isScalaCli = bspSession.exists(_.main.isScalaCLI)
    def isScalaFile =
      file.toString.isScala || file.isJava || file.isAmmoniteScript
    if (
      isScalaCli && isScalaFile &&
      buildTargets.inverseSources(file).isEmpty &&
      file.toNIO.startsWith(folder.toNIO) &&
      !ScalaCliBspScope.inScope(folder, file)
    ) {
      languageClient
        .showMessageRequest(
          FileOutOfScalaCliBspScope.askToRegenerateConfigAndRestartBsp(
            file.toNIO
          )
        )
        .asScala
        .flatMap {
          case FileOutOfScalaCliBspScope.regenerateAndRestart =>
            val buildTool = ScalaCliBuildTool(folder, folder, () => userConfig)
            for {
              _ <- buildTool.generateBspConfig(
                folder,
                bspConfigGenerator.runUnconditionally(buildTool, _),
                statusBar,
              )
              _ <- quickConnectToBuildServer()
            } yield ()
          case _ => Future.successful(())
        }
    } else Future.successful(())
  }

  override protected def onChange(paths: Seq[AbsolutePath]): Future[Unit] = {
    val superOnChange = super.onChange(paths)
    Future
      .sequence(
        List(
          superOnChange,
          onBuildChanged(paths).ignoreValue,
          Future.sequence(paths.map(onBuildToolAdded)),
        )
      )
      .ignoreValue
  }

  private def onBuildChangedUnbatched(
      paths: Seq[AbsolutePath]
  ): Future[BuildChange] = {
    val changedBuilds = paths.flatMap(buildTools.isBuildRelated)
    val buildChange = for {
      chosenBuildTool <- tables.buildTool.selectedBuildTool()
      if (changedBuilds.contains(chosenBuildTool))
    } yield slowConnectToBuildServer(forceImport = false)
    buildChange.getOrElse(Future.successful(BuildChange.None))
  }

  private def onBuildToolAdded(
      path: AbsolutePath
  ): Future[BuildChange] = {
    val supportedBuildTools = buildTools.loadSupported()
    val maybeBuildChange = for {
      currentBuildToolName <- tables.buildTool.selectedBuildTool()
      currentBuildTool <- supportedBuildTools.find(
        _.executableName == currentBuildToolName
      )
      addedBuildName <- buildTools.isBuildRelated(path)
      if (buildTools.newBuildTool(addedBuildName))
      if (addedBuildName != currentBuildToolName)
      newBuildTool <- supportedBuildTools.find(
        _.executableName == addedBuildName
      )
    } yield {
      buildToolSelector
        .onNewBuildToolAdded(newBuildTool, currentBuildTool)
        .flatMap { switch =>
          if (switch) slowConnectToBuildServer(forceImport = false)
          else Future.successful(BuildChange.None)
        }
    }
    maybeBuildChange.getOrElse(Future.successful(BuildChange.None))
  }

  /**
   * Callback that is executed on a file change event by the file watcher.
   *
   * Note that if you are adding processing of another kind of a file, be sure
   * to include it in the [[fileWatchFilter]]
   *
   * This method is run synchronously in the FileWatcher, so it should not do
   * anything expensive on the main thread
   */
  override protected def didChangeWatchedFiles(
      event: FileWatcherEvent
  ): CompletableFuture[Unit] = {
    val path = AbsolutePath(event.path)
    event.eventType match {
      case EventType.CreateOrModify
          if path.isInBspDirectory(folder) && path.extension == "json"
            && isValidBspFile(path) =>
        scribe.info(s"Detected new build tool in $path")
        quickConnectToBuildServer()
      case _ =>
    }
    if (path.isBuild) {
      onBuildChanged(List(path)).ignoreValue.asJava
    } else {
      super.didChangeWatchedFiles(event)
    }
  }

  override protected def onDeleteWatchedFiles(
      files: List[AbsolutePath]
  ): Unit = {
    val (bloopReportDelete, otherDeleteEvents) =
      files.partition(_.toNIO.startsWith(reports.bloop.maybeReportsDir))
    if (bloopReportDelete.nonEmpty) connectionBspStatus.onReportsUpdate()
    super.onDeleteWatchedFiles(otherDeleteEvents)
  }

  private def isValidBspFile(path: AbsolutePath): Boolean =
    path.readTextOpt.exists(text => Try(ujson.read(text)).toOption.nonEmpty)

  override def formatting(
      params: DocumentFormattingParams
  ): CompletableFuture[util.List[TextEdit]] =
    CancelTokens.future { token =>
      val path = params.getTextDocument.getUri.toAbsolutePath
      if (path.isJava)
        javaFormattingProvider.format(params)
      else
        for {
          projectRoot <- calculateOptProjectRoot().map(_.getOrElse(folder))
          res <- formattingProvider.format(path, projectRoot, token)
        } yield res
    }

  override def onTypeFormatting(
      params: DocumentOnTypeFormattingParams
  ): CompletableFuture[util.List[TextEdit]] =
    CancelTokens { _ =>
      val path = params.getTextDocument.getUri.toAbsolutePath
      if (path.isJava)
        javaFormattingProvider.format()
      else
        onTypeFormattingProvider.format(params).asJava
    }

  override def rangeFormatting(
      params: DocumentRangeFormattingParams
  ): CompletableFuture[util.List[TextEdit]] =
    CancelTokens { _ =>
      val path = params.getTextDocument.getUri.toAbsolutePath
      if (path.isJava)
        javaFormattingProvider.format(params)
      else
        rangeFormattingProvider.format(params).asJava
    }

  def restartBspServer(): Future[Boolean] = {
    def emitMessage(msg: String) = {
      languageClient.showMessage(new MessageParams(MessageType.Warning, msg))
    }
    // This is for `bloop` and `sbt`, for which `build/shutdown` doesn't shutdown the server.
    val shutdownBsp =
      bspSession match {
        case Some(session) if session.main.isBloop =>
          for {
            _ <- disconnectOldBuildServer()
          } yield bloopServers.shutdownServer()
        case Some(session) if session.main.isSbt =>
          for {
            currentBuildTool <- buildTool()
            res <- currentBuildTool match {
              case Some(sbt: SbtBuildTool) =>
                for {
                  _ <- disconnectOldBuildServer()
                  code <- sbt.shutdownBspServer(shellRunner)
                } yield code == 0
              case _ => Future.successful(false)
            }
          } yield res
        case s => Future.successful(s.nonEmpty)
      }

    for {
      didShutdown <- shutdownBsp
      _ = if (!didShutdown) {
        bspSession match {
          case Some(session) =>
            emitMessage(
              s"Could not shutdown ${session.main.name} server. Will try to reconnect."
            )
          case None =>
            emitMessage("No build server connected. Will try to connect.")
        }
      }
      _ <- autoConnectToBuildServer()
    } yield didShutdown
  }

  def switchBspServer(): Future[Unit] =
    for {
      isSwitched <- bspConnector.switchBuildServer(
        folder,
        () => slowConnectToBuildServer(forceImport = true),
      )
      _ <- {
        if (isSwitched) quickConnectToBuildServer()
        else Future.successful(())
      }
    } yield ()

  def resetPopupChoice(value: String): Future[Unit] =
    popupChoiceReset.reset(value)

  def interactivePopupChoiceReset(): Future[Unit] =
    popupChoiceReset.interactiveReset()

  def generateBspConfig(): Future[Unit] = {
    val servers: List[BuildServerProvider] =
      buildTools.loadSupported().collect {
        case buildTool: BuildServerProvider => buildTool
      }

    def ensureAndConnect(
        buildTool: BuildServerProvider,
        status: BspConfigGenerationStatus,
    ): Unit =
      status match {
        case Generated =>
          tables.buildServers.chooseServer(buildTool.getBuildServerName)
          quickConnectToBuildServer().ignoreValue
        case Cancelled => ()
        case Failed(exit) =>
          exit match {
            case Left(exitCode) =>
              scribe.error(
                s"Create of .bsp failed with exit code: $exitCode"
              )
              languageClient.showMessage(
                Messages.BspProvider.genericUnableToCreateConfig
              )
            case Right(message) =>
              languageClient.showMessage(
                Messages.BspProvider.unableToCreateConfigFromMessage(
                  message
                )
              )
          }
      }

    (servers match {
      case Nil =>
        scribe.warn(Messages.BspProvider.noBuildToolFound.toString())
        languageClient.showMessage(Messages.BspProvider.noBuildToolFound)
        Future.successful(())
      case buildTool :: Nil =>
        buildTool
          .generateBspConfig(
            folder,
            args =>
              bspConfigGenerator.runUnconditionally(
                buildTool,
                args,
              ),
            statusBar,
          )
          .map(status => ensureAndConnect(buildTool, status))
      case buildTools =>
        bspConfigGenerator
          .chooseAndGenerate(buildTools)
          .map {
            case (
                  buildTool: BuildServerProvider,
                  status: BspConfigGenerationStatus,
                ) =>
              ensureAndConnect(buildTool, status)
          }
    })
  }

  private def buildTool(): Future[Option[BuildTool]] = {
    buildTools.loadSupported match {
      case Nil => Future(None)
      case buildTools =>
        for {
          buildTool <- buildToolSelector.checkForChosenBuildTool(
            buildTools
          )
        } yield buildTool.filter(isCompatibleVersion)
    }
  }

  private def isCompatibleVersion(buildTool: BuildTool) =
    SemVer.isCompatibleVersion(
      buildTool.minimumVersion,
      buildTool.version,
    )

  def supportedBuildTool(): Future[Option[BuildTool.Found]] = {
    def isCompatibleVersion(buildTool: BuildTool) = {
      val isCompatibleVersion = this.isCompatibleVersion(buildTool)
      if (isCompatibleVersion) {
        buildTool.digest(folder) match {
          case Some(digest) =>
            Some(BuildTool.Found(buildTool, digest))
          case None =>
            scribe.warn(
              s"Could not calculate checksum for ${buildTool.executableName} in $folder"
            )
            None
        }
      } else {
        scribe.warn(s"Unsupported $buildTool version ${buildTool.version}")
        languageClient.showMessage(
          Messages.IncompatibleBuildToolVersion.params(buildTool)
        )
        None
      }
    }

    buildTools.loadSupported match {
      case Nil => {
        if (!buildTools.isAutoConnectable()) {
          warnings.noBuildTool()
        }
        // wait for a bsp file to show up
        fileWatcher.start(Set(folder.resolve(".bsp")))
        Future(None)
      }
      case buildTools =>
        for {
          buildTool <- buildToolSelector.checkForChosenBuildTool(
            buildTools
          )
        } yield {
          buildTool.flatMap(isCompatibleVersion)
        }
    }
  }

  def slowConnectToBuildServer(
      forceImport: Boolean
  ): Future[BuildChange] =
    for {
      possibleBuildTool <- supportedBuildTool()
      chosenBuildServer = tables.buildServers.selectedServer()
      isBloopOrEmpty = chosenBuildServer.isEmpty || chosenBuildServer.exists(
        _ == BloopServers.name
      )
      buildChange <- possibleBuildTool match {
        case Some(BuildTool.Found(buildTool: ScalaCliBuildTool, _))
            if chosenBuildServer.isEmpty =>
          tables.buildServers.chooseServer(ScalaCliBuildTool.name)
          val scalaCliBspConfigExists =
            ScalaCliBuildTool.pathsToScalaCliBsp(folder).exists(_.isFile)
          if (scalaCliBspConfigExists) Future.successful(BuildChange.None)
          else
            buildTool
              .generateBspConfig(
                folder,
                args => bspConfigGenerator.runUnconditionally(buildTool, args),
                statusBar,
              )
              .flatMap(_ => quickConnectToBuildServer())
        case Some(found) if isBloopOrEmpty =>
          slowConnectToBloopServer(forceImport, found.buildTool, found.digest)
        case Some(found) =>
          indexer.reloadWorkspaceAndIndex(
            forceImport,
            found.buildTool,
            found.digest,
            importBuild,
          )
        case None =>
          Future.successful(BuildChange.None)

      }
    } yield buildChange

  /**
   * If there is no auto-connectable build server and no supported build tool is found
   * we assume it's a scala-cli project.
   */
  def maybeSetupScalaCli(): Future[Unit] = {
    if (
      !buildTools.isAutoConnectable()
      && buildTools.loadSupported.isEmpty
      && folder.isScalaProject()
    ) scalaCli.setupIDE(folder)
    else Future.successful(())
  }

  private def slowConnectToBloopServer(
      forceImport: Boolean,
      buildTool: BuildTool,
      checksum: String,
  ): Future[BuildChange] =
    for {
      result <- {
        if (forceImport)
          bloopInstall.runUnconditionally(buildTool, isImportInProcess)
        else bloopInstall.runIfApproved(buildTool, checksum, isImportInProcess)
      }
      change <- {
        if (result.isInstalled) quickConnectToBuildServer()
        else if (result.isFailed) {
          for {
            maybeProjectRoot <- calculateOptProjectRoot()
            change <-
              if (buildTools.isAutoConnectable(maybeProjectRoot)) {
                // TODO(olafur) try to connect but gracefully error
                languageClient.showMessage(
                  Messages.ImportProjectPartiallyFailed
                )
                // Connect nevertheless, many build import failures are caused
                // by resolution errors in one weird module while other modules
                // exported successfully.
                quickConnectToBuildServer()
              } else {
                languageClient.showMessage(Messages.ImportProjectFailed)
                Future.successful(BuildChange.Failed)
              }
          } yield change
        } else {
          Future.successful(BuildChange.None)
        }

      }
    } yield change

  def calculateOptProjectRoot(): Future[Option[AbsolutePath]] =
    for {
      possibleBuildTool <- buildTool()
    } yield possibleBuildTool.map(_.projectRoot).orElse(buildTools.bloopProject)

  def quickConnectToBuildServer(): Future[BuildChange] =
    for {
      optRoot <- calculateOptProjectRoot()
      change <-
        if (!buildTools.isAutoConnectable(optRoot)) {
          scribe.warn("Build server is not auto-connectable.")
          Future.successful(BuildChange.None)
        } else {
          autoConnectToBuildServer()
        }
    } yield {
      buildServerPromise.trySuccess(())
      change
    }

  override protected def onBuildTargetChanges(
      params: b.DidChangeBuildTarget
  ): Unit = {
    super.onBuildTargetChanges(params)
    val (ammoniteChanges, otherChanges) =
      params.getChanges.asScala.partition { change =>
        val connOpt = buildTargets.buildServerOf(change.getTarget)
        connOpt.nonEmpty && connOpt == ammonite.buildServer
      }

    if (ammoniteChanges.nonEmpty)
      ammonite.importBuild().onComplete {
        case Success(()) =>
        case Failure(exception) =>
          scribe.error("Error re-importing Ammonite build", exception)
      }

    val scalaCliServers = scalaCli.servers
    val mainConnectionChanges = otherChanges.filterNot { change =>
      val connOpt = buildTargets.buildServerOf(change.getTarget)
      connOpt.exists(conn => scalaCliServers.exists(_ == conn))
    }
    if (mainConnectionChanges.nonEmpty) {
      bspSession match {
        case None => scribe.warn("No build server connected")
        case Some(session) =>
          for {
            _ <- importBuild(session)
            _ <- indexer.profiledIndexWorkspace(workspaceCheck)
          } {
            focusedDocument().foreach(path => compilations.compileFile(path))
          }
      }
    }
  }

  def autoConnectToBuildServer(): Future[BuildChange] = {
    def compileAllOpenFiles: BuildChange => Future[BuildChange] = {
      case change if !change.isFailed =>
        Future
          .sequence(
            compilations
              .cascadeCompileFiles(buffers.open.toSeq)
              .ignoreValue ::
              compilers.load(buffers.open.toSeq) ::
              Nil
          )
          .map(_ => change)
      case other => Future.successful(other)
    }

    val scalaCliPaths = scalaCli.paths

    (for {
      _ <- disconnectOldBuildServer()
      maybeProjectRoot <- calculateOptProjectRoot()
      maybeSession <- timerProvider.timed("Connected to build server", true) {
        bspConnector.connect(
          maybeProjectRoot.getOrElse(folder),
          folder,
          userConfig,
          shellRunner,
        )
      }
      result <- maybeSession match {
        case Some(session) =>
          val result = connectToNewBuildServer(session)
          session.mainConnection.onReconnection { newMainConn =>
            val updSession = session.copy(main = newMainConn)
            connectToNewBuildServer(updSession)
              .flatMap(compileAllOpenFiles)
              .ignoreValue
          }
          result
        case None =>
          Future.successful(BuildChange.None)
      }
      _ <- Future.sequence(
        scalaCliPaths
          .collect {
            case path if (!conflictsWithMainBsp(path.toNIO)) =>
              scalaCli.start(path)
          }
      )
      _ = initTreeView()
    } yield result)
      .recover { case NonFatal(e) =>
        disconnectOldBuildServer()
        val message =
          "Failed to connect with build server, no functionality will work."
        val details = " See logs for more details."
        languageClient.showMessage(
          new MessageParams(MessageType.Error, message + details)
        )
        scribe.error(message, e)
        BuildChange.Failed
      }
      .flatMap(compileAllOpenFiles)
  }

  def disconnectOldBuildServer(): Future[Unit] = {
    compilations.cancel()
    buildTargetClasses.cancel()
    diagnostics.reset()
    bspSession.foreach(connection =>
      scribe.info(s"Disconnecting from ${connection.main.name} session...")
    )

    for {
      _ <- scalaCli.stop()
      _ <- bspSession match {
        case None => Future.successful(())
        case Some(session) =>
          currentBspSession = None
          mainBuildTargetsData.resetConnections(List.empty)
          session.shutdown()
      }
    } yield ()
  }

  private def importBuild(session: BspSession) = {
    val importedBuilds0 = timerProvider.timed("Imported build") {
      session.importBuilds()
    }
    for {
      bspBuilds <- statusBar.trackFuture("Importing build", importedBuilds0)
      _ = {
        val idToConnection = bspBuilds.flatMap { bspBuild =>
          val targets =
            bspBuild.build.workspaceBuildTargets.getTargets().asScala
          targets.map(t => (t.getId(), bspBuild.connection))
        }
        mainBuildTargetsData.resetConnections(idToConnection)
      }
    } yield compilers.cancel()
  }

  private def connectToNewBuildServer(
      session: BspSession
  ): Future[BuildChange] = {
    scribe.info(
      s"Connected to Build server: ${session.main.name} v${session.version}"
    )
    cancelables.add(session)
    currentBspSession = Some(session)
    for {
      _ <- importBuild(session)
      _ <- indexer.profiledIndexWorkspace(workspaceCheck)
      _ = if (session.main.isBloop) checkRunningBloopVersion(session.version)
    } yield {
      BuildChange.Reconnected
    }
  }

  private def checkRunningBloopVersion(bspServerVersion: String) = {
    if (doctor.isUnsupportedBloopVersion()) {
      val notification = tables.dismissedNotifications.IncompatibleBloop
      if (!notification.isDismissed) {
        val messageParams = IncompatibleBloopVersion.params(
          bspServerVersion,
          BuildInfo.bloopVersion,
          isChangedInSettings = userConfig.bloopVersion != None,
        )
        languageClient.showMessageRequest(messageParams).asScala.foreach {
          case action if action == IncompatibleBloopVersion.shutdown =>
            bloopServers.shutdownServer()
            autoConnectToBuildServer()
          case action if action == IncompatibleBloopVersion.dismissForever =>
            notification.dismissForever()
          case _ =>
        }
      }
    }
  }

  def ammoniteStart(): Future[Unit] = ammonite.start()
  def ammoniteStop(): Future[Unit] = ammonite.stop()

  override protected def isMillBuildSc(path: AbsolutePath): Boolean =
    path.toNIO.getFileName.toString == "build.sc" &&
      // for now, this only checks for build.sc, but this could be made more strict in the future
      // (require ./mill or ./.mill-version)
      buildTools.isMill

  def maybeImportScript(path: AbsolutePath): Option[Future[Unit]] = {
    val scalaCliPath = scalaCliDirOrFile(path)
    if (
      !path.isAmmoniteScript ||
      !buildTargets.inverseSources(path).isEmpty ||
      ammonite.loaded(path) ||
      scalaCli.loaded(scalaCliPath) ||
      isMillBuildSc(path)
    )
      None
    else {
      def doImportScalaCli(): Future[Unit] =
        scalaCli
          .start(scalaCliPath)
          .map { _ =>
            languageClient.showMessage(
              Messages.ImportScalaScript.ImportedScalaCli
            )
          }
          .recover { e =>
            languageClient.showMessage(
              Messages.ImportScalaScript.ImportFailed(path.toString)
            )
            scribe.warn(s"Error importing Scala CLI project $scalaCliPath", e)
          }
      def doImportAmmonite(): Future[Unit] =
        ammonite
          .start(Some(path))
          .map { _ =>
            languageClient.showMessage(
              Messages.ImportScalaScript.ImportedAmmonite
            )
          }
          .recover { e =>
            languageClient.showMessage(
              Messages.ImportScalaScript.ImportFailed(path.toString)
            )
            scribe.warn(s"Error importing Ammonite script $path", e)
          }

      val autoImportAmmonite =
        tables.dismissedNotifications.AmmoniteImportAuto.isDismissed
      val autoImportScalaCli =
        tables.dismissedNotifications.ScalaCliImportAuto.isDismissed

      def askAutoImport(notification: DismissedNotifications#Notification) =
        languageClient
          .showMessageRequest(Messages.ImportAllScripts.params())
          .asScala
          .onComplete {
            case Failure(e) =>
              scribe.warn("Error requesting automatic Scala scripts import", e)
            case Success(null) =>
              scribe.debug("Automatic Scala scripts import cancelled by user")
            case Success(resp) =>
              resp.getTitle match {
                case Messages.ImportAllScripts.importAll =>
                  notification.dismissForever()
                case _ =>
              }
          }

      val futureRes =
        if (autoImportAmmonite) {
          doImportAmmonite()
        } else if (autoImportScalaCli) {
          doImportScalaCli()
        } else {
          languageClient
            .showMessageRequest(Messages.ImportScalaScript.params())
            .asScala
            .flatMap { response =>
              if (response != null)
                response.getTitle match {
                  case Messages.ImportScalaScript.doImportAmmonite =>
                    askAutoImport(
                      tables.dismissedNotifications.AmmoniteImportAuto
                    )
                    doImportAmmonite()
                  case Messages.ImportScalaScript.doImportScalaCli =>
                    askAutoImport(
                      tables.dismissedNotifications.ScalaCliImportAuto
                    )
                    doImportScalaCli()
                  case _ => Future.unit
                }
              else {
                Future.unit
              }
            }
            .recover { e =>
              scribe.warn("Error requesting Scala script import", e)
            }
        }
      Some(futureRes)
    }
  }

  /**
   * Returns the absolute path or directory that ScalaCLI imports as ScalaCLI
   * scripts. By default, ScalaCLI tries to import the entire directory as
   * ScalaCLI scripts. However, we have to ensure that there are no clashes with
   * other existing sourceItems see:
   * https://github.com/scalameta/metals/issues/4447
   *
   * @param path
   *   the absolute path of the ScalaCLI script to import
   */
  override protected def scalaCliDirOrFile(path: AbsolutePath): AbsolutePath = {
    val dir = path.parent
    val nioDir = dir.toNIO
    if (conflictsWithMainBsp(nioDir)) path else dir
  }

  private def conflictsWithMainBsp(nioDir: Path) =
    buildTargets.sourceItems.filter(_.exists).exists { item =>
      val nioItem = item.toNIO
      nioDir.startsWith(nioItem) || nioItem.startsWith(nioDir)
    }

  private def clearBloopDir(folder: AbsolutePath): Unit = {
    try BloopDir.clear(folder)
    catch {
      case e: Throwable =>
        languageClient.showMessage(Messages.ResetWorkspaceFailed)
        scribe.error("Error while deleting directories inside .bloop", e)
    }
  }

  override def resetWorkspace(): Future[Unit] =
    for {
      maybeProjectRoot <- calculateOptProjectRoot()
      _ <- disconnectOldBuildServer()
      _ = maybeProjectRoot match {
        case Some(path) if buildTools.isBloop(path) =>
          bloopServers.shutdownServer()
          clearBloopDir(path)
        case _ =>
      }
      _ = tables.cleanAll()
      _ <- autoConnectToBuildServer().map(_ => ())
    } yield ()

  override def workspaceCheck(): Unit = {
    doctor.check(headDoctor)
    buildTools
      .loadSupported()
      .map(_.projectRoot)
      .distinct match {
      case Nil => formattingProvider.validateWorkspace(path)
      case paths =>
        for {
          path <- paths
        } formattingProvider.validateWorkspace(path)
    }
  }

}
