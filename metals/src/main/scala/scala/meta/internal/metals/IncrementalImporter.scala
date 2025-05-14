package scala.meta.internal.metals

import scala.concurrent.ExecutionContext
import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.mtags.ScalametaCommonEnrichments._
import scala.meta.io.AbsolutePath
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.services.LanguageClient

import java.util.concurrent.atomic.AtomicInteger

class IncrementalImporter(
    languageClient: LanguageClient,
    workDoneProgress: WorkDoneProgress,
    compilations: Compilations,
    focusedDocument: () => Option[AbsolutePath],
    buildTargets: BuildTargets,
)(implicit ec: ExecutionContext) {

  private val runningImports = new AtomicInteger(0);

  def signalImportBuildStarted(): Unit = {
    runningImports.incrementAndGet()
  }

  def signalImportBuildFinished(): Unit = {
    if (runningImports.decrementAndGet() == 0) {
      focusedDocument().foreach(maybePromptForImport)
    }
  }

  def maybePromptForImport(path: AbsolutePath): Unit = {
    if (runningImports.get() == 0 && shouldSendNotification(path)) {
      doSendMessage(path)
    }
  }

  private def shouldSendNotification(path: AbsolutePath): Boolean = {
    path.isScala && buildTargets.inverseSources(path).isEmpty
  }

  private def handleImportClick(
      item: MessageActionItem,
      path: AbsolutePath,
  ): Unit = {
    // If the user clicks "Import", expand the file
    // null means the user dismissed the dialog
    if (item != null) {
      signalImportBuildStarted()
      workDoneProgress
        .trackFuture(
          "Discovering build targets",
          compilations.expand(Seq(path)),
        )
        .andThen { case _ => signalImportBuildFinished() }
    }
  }

  private def doSendMessage(path: AbsolutePath): Unit = {
    val params = new ShowMessageRequestParams()
    params.setMessage(
      s"${path.filename} has not been imported yet. Import it to get IDE features like code completion, type information, etc."
    )
    params.setType(MessageType.Warning)
    params.setActions(List(new MessageActionItem("Import")).asJava)
    languageClient.showMessageRequest(params).thenApply { item =>
      handleImportClick(item, path)
    }
  }
}
