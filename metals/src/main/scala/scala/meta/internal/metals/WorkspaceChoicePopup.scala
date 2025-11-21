package scala.meta.internal.metals

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.clients.language.MetalsLanguageClient

import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.ShowMessageRequestParams

class WorkspaceChoicePopup(
    folders: () => List[ProjectMetalsLspService],
    languageClient: MetalsLanguageClient,
) {
  def interactiveChooseFolder(
      actionName: String
  )(implicit ec: ExecutionContext): Future[Option[ProjectMetalsLspService]] = {

    val currentFolders = folders()
    if (currentFolders.length == 1) Future.successful(currentFolders.headOption)
    else {
      languageClient
        .showMessageRequest(
          WorkspaceChoicePopup
            .choicesParams(actionName, currentFolders.map(_.getVisibleName)),
          defaultTo = () => {
            val first = currentFolders.headOption.getOrElse(
              throw new IllegalStateException("No folders found")
            )
            new MessageActionItem(first.getVisibleName)
          },
        )
        .asScala
        .map { item =>
          currentFolders.find(_.getVisibleName == item.getTitle())
        }
    }
  }
}

object WorkspaceChoicePopup {
  def choicesParams(
      actionName: String,
      folders: List[String],
  ): ShowMessageRequestParams = {
    val params = new ShowMessageRequestParams()

    val lowerCaseActionName =
      if (actionName.nonEmpty) s"${actionName.head.toLower}${actionName.tail}"
      else actionName

    params.setMessage(
      s"For which folder would you like to $lowerCaseActionName?"
    )
    params.setType(MessageType.Info)
    params.setActions(
      folders
        .map(folder => new MessageActionItem(folder))
        .asJava
    )
    params
  }
}
