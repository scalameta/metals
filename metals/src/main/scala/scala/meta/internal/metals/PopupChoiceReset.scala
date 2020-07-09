package scala.meta.internal.metals

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.MetalsEnrichments._

import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.ShowMessageRequestParams

class PopupChoiceReset(
    tables: Tables,
    languageClient: MetalsLanguageClient,
    doctor: Doctor,
    reconnectToBuildServer: () => Future[Unit]
) {
  import PopupChoiceReset._

  def reset(value: String)(implicit ec: ExecutionContext): Future[Unit] = {
    val result = if (value == BuildTool) {
      tables.buildTool.reset()
      reconnectToBuildServer()
    } else if (value == BuildImport) {
      tables.dismissedNotifications.ImportChanges.reset()
      Future.successful(())
    } else {
      Future.successful(())
    }
    result.foreach(_ => doctor.executeRefreshDoctor())
    result
  }

  def interactiveReset()(implicit ec: ExecutionContext): Future[Unit] = {
    def choicesParams(): ShowMessageRequestParams = {
      val params = new ShowMessageRequestParams()
      params.setMessage(
        "What choice would you like to reset?"
      )
      params.setType(MessageType.Info)
      params.setActions(
        List(
          new MessageActionItem(PopupChoiceReset.BuildTool),
          new MessageActionItem(PopupChoiceReset.BuildImport)
        ).asJava
      )
      params
    }

    languageClient
      .showMessageRequest(choicesParams())
      .asScala
      .flatMap { item =>
        if (item.getTitle() == PopupChoiceReset.BuildTool) {
          reset(PopupChoiceReset.BuildTool)
        } else if (item.getTitle() == PopupChoiceReset.BuildImport) {
          reset(PopupChoiceReset.BuildImport)
        } else {
          Future.successful(())
        }
      }
  }
}

object PopupChoiceReset {
  final val BuildTool = "build tool selection"
  final val BuildImport = "build import"
}
