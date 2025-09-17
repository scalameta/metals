package scala.meta.internal.metals

import java.nio.file.Files
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.concurrent.Promise

import scala.meta.infra.FeatureFlagProvider
import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.metals.Indexer.BuildTool
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.clients.language.ConfiguredLanguageClient
import scala.meta.internal.metals.doctor.HeadDoctor
import scala.meta.internal.metals.doctor.MetalsServiceInfo
import scala.meta.internal.metals.watcher.FileWatcher
import scala.meta.internal.metals.watcher.NoopFileWatcher
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.DidChangeBuildTarget
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.InitializeParams

class FallbackMetalsLspService(
    ec: ExecutionContextExecutorService,
    override val sh: ScheduledExecutorService,
    serverInputs: MetalsServerInputs,
    override val languageClient: ConfiguredLanguageClient,
    initializeParams: InitializeParams,
    override val clientConfig: ClientConfiguration,
    override val statusBar: StatusBar,
    focusedDocument: () => Option[AbsolutePath],
    shellRunner: ShellRunner,
    override val timerProvider: TimerProvider,
    override val folder: AbsolutePath,
    folderVisibleName: Option[String],
    headDoctor: HeadDoctor,
    override val workDoneProgress: WorkDoneProgress,
    bspStatus: BspStatus,
    featureFlags: FeatureFlagProvider,
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
      folder,
      folderVisibleName,
      headDoctor,
      bspStatus,
      workDoneProgress,
      maxScalaCliServers = 10,
      featureFlags,
    ) {

  val buildServerPromise: Promise[Unit] = Promise.successful(())
  indexingPromise.success(())

  private val files: AtomicReference[Set[AbsolutePath]] = new AtomicReference(
    Set.empty
  )
  override protected val semanticdbs: Semanticdbs = interactiveSemanticdbs
  override val fileWatcher: FileWatcher = NoopFileWatcher
  override val projectInfo: MetalsServiceInfo =
    MetalsServiceInfo.FallbackService

  override val indexer: Indexer = Indexer(this)

  def buildData(): Seq[BuildTool] =
    scalaCli.lastImportedBuilds.map {
      case (lastImportedBuild, buildTargetsData) =>
        Indexer
          .BuildTool("scala-cli", buildTargetsData, lastImportedBuild)
    }

  override def didClose(params: DidCloseTextDocumentParams): Unit = {
    val path = params.getTextDocument.getUri.toAbsolutePath
    files.getAndUpdate(_ - path)
    super.didClose(params)
    scalaCli.stop(path).map(_ => diagnostics.didDelete(path))
  }

  override def maybeImportFileAndLoad(
      path: AbsolutePath,
      load: () => Future[Unit],
  ): Future[Unit] =
    for {
      _ <-
        if (!path.isScala) Future.unit
        else {
          val prev = files.getAndUpdate(_ + path)
          if (prev.contains(path)) Future.unit
          else scalaCli.start(path)
        }
      _ <- load()
    } yield ()

  override protected def onBuildTargetChanges(
      params: DidChangeBuildTarget
  ): Unit = {
    compilations.cancel()
    val scalaCliAffectedServers = params.getChanges.asScala
      .flatMap { change =>
        buildTargets.buildServerOf(change.getTarget)
      }
      .flatMap(conn => scalaCli.servers.find(_ == conn))
    importAfterScalaCliChanges(scalaCliAffectedServers)
  }

  override protected def onInitialized(): Future[Unit] = Future.unit
}

object FallbackMetalsLspService {
  def path(): AbsolutePath = {
    val uri = Files.createTempDirectory(s"fallback-service")
    scribe.debug(s"creating tmp directory $uri for fallback service in")
    uri.toFile.deleteOnExit()
    AbsolutePath(uri)
  }
}
