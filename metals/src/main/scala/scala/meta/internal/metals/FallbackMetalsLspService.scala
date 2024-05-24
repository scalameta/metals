package scala.meta.internal.metals

import java.nio.file.Files
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future

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
    sh: ScheduledExecutorService,
    serverInputs: MetalsServerInputs,
    languageClient: ConfiguredLanguageClient,
    initializeParams: InitializeParams,
    clientConfig: ClientConfiguration,
    statusBar: StatusBar,
    focusedDocument: () => Option[AbsolutePath],
    shellRunner: ShellRunner,
    timerProvider: TimerProvider,
    folder: AbsolutePath,
    folderVisibleName: Option[String],
    headDoctor: HeadDoctor,
    workDoneProgress: WorkDoneProgress,
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
      folder,
      folderVisibleName,
      headDoctor,
      bspStatus,
      workDoneProgress,
      maxScalaCliServers = 10,
    ) {

  buildServerPromise.success(())
  indexingPromise.success(())

  private val files: AtomicReference[Set[AbsolutePath]] = new AtomicReference(
    Set.empty
  )

  override val pauseables: Pauseable = Pauseable.fromPausables(
    parseTrees ::
      compilations.pauseables
  )
  override protected val semanticdbs: Semanticdbs = interactiveSemanticdbs
  override val fileWatcher: FileWatcher = NoopFileWatcher
  override val projectInfo: MetalsServiceInfo =
    MetalsServiceInfo.FallbackService

  protected def buildData(): Seq[BuildTool] =
    scalaCli.lastImportedBuilds.map {
      case (lastImportedBuild, buildTargetsData) =>
        Indexer
          .BuildTool("scala-cli", buildTargetsData, lastImportedBuild)
    }

  override def didClose(params: DidCloseTextDocumentParams): Unit = {
    val path = params.getTextDocument.getUri.toAbsolutePath
    files.getAndUpdate(_ - path)
    super.didClose(params)
    scalaCli.stop(path)
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
