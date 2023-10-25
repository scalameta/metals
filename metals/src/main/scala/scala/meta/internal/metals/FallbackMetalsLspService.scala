package scala.meta.internal.metals

import java.nio.file.Files
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future

import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.clients.language.ConfiguredLanguageClient
import scala.meta.internal.metals.doctor.HeadDoctor
import scala.meta.io.AbsolutePath

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
    initTreeView: () => Unit,
    folder: AbsolutePath,
    folderVisibleName: Option[String],
    headDoctor: HeadDoctor,
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
      maxScalaCliServers = 10,
    ) {

  buildServerPromise.success(())
  indexingPromise.success(())

  private val files: AtomicReference[Set[AbsolutePath]] = new AtomicReference(
    Set.empty
  )

  override def maybeImportScript(path: AbsolutePath): Option[Future[Unit]] = {
    if (!path.isScala) None
    else {
      val prev = files.getAndUpdate(_ + path)
      if (prev.contains(path)) None
      else Some(scalaCli.start(path))
    }
  }

  override def didClose(params: DidCloseTextDocumentParams): Unit = {
    val path = params.getTextDocument.getUri.toAbsolutePath
    files.getAndUpdate(_ - path)
    super.didClose(params)
    scalaCli.stop(path)
  }

}

object FallbackMetalsLspService {
  def path(): AbsolutePath = {
    val uri = Files.createTempDirectory(s"fallback-service")
    scribe.debug(s"creating tmp directory $uri for fallback service in")
    uri.toFile.deleteOnExit()
    AbsolutePath(uri)
  }
}
