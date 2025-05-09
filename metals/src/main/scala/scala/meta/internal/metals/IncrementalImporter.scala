package scala.meta.internal.metals

import scala.concurrent.ExecutionContext

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.mtags.ScalametaCommonEnrichments._
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.services.LanguageClient

class IncrementalImporter(
    languageClient: LanguageClient,
    workDoneProgress: WorkDoneProgress,
    compilations: Compilations,
)(implicit ec: ExecutionContext) {
  def promptForImport(path: AbsolutePath): Unit = {
    val params = new ShowMessageRequestParams()
    params.setMessage(
      s"${path.filename} has not been imported yet. Import it to get IDE features like code completion, type information, etc."
    )
    params.setType(MessageType.Warning)
    params.setActions(List(new MessageActionItem("Import")).asJava)
    languageClient.showMessageRequest(params).thenApply { item =>
      // If the user clicks "Import", expand the file
      // null means the user dismissed the dialog
      if (item != null) {
        workDoneProgress.trackFuture(
          "Discovering build targets",
          compilations.expand(Seq(path)),
        )
      }
    }
  }
}
