package scala.meta.internal.metals

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.MetalsEnrichments._

import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.services.LanguageClient

class WorkspaceChoicePopup(
    folders: () => List[MetalsLspService],
    languageClient: LanguageClient,
) {
  def interactiveChooseFolder(
      actionName: String
  )(implicit ec: ExecutionContext): Future[Option[MetalsLspService]] = {
    def choicesParams(): ShowMessageRequestParams = {
      val params = new ShowMessageRequestParams()
      params.setMessage(
        s"For which folder would you like to $actionName?"
      )
      params.setType(MessageType.Info)
      params.setActions(
        folders()
          .map(folder => new MessageActionItem(folder.getVisibleName))
          .asJava
      )
      params
    }

    val currentFolders = folders()
    if (currentFolders.length == 1) Future.successful(currentFolders.headOption)
    else {
      languageClient
        .showMessageRequest(choicesParams())
        .asScala
        .map { item => currentFolders.find(_.getVisibleName == item) }
    }
  }
}
