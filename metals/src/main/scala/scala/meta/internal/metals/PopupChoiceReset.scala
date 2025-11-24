package scala.meta.internal.metals

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.bsp.BuildChange
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.clients.language.MetalsLanguageClient

import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.ShowMessageRequestParams

class PopupChoiceReset(
    tables: Tables,
    languageClient: MetalsLanguageClient,
    executeRefreshDoctor: () => Unit,
    slowConnect: () => Future[BuildChange],
    switchBspServer: () => Future[BuildChange],
) {
  import PopupChoiceReset._

  def reset(value: String)(implicit ec: ExecutionContext): Future[Unit] = {
    val result = if (value == BuildTool || value == All) {
      scribe.info("Resetting build tool selection.")
      tables.buildTool.reset()
      slowConnect().ignoreValue
    } else if (value == BuildImport || value == All) {
      tables.dismissedNotifications.ImportChanges.reset()
      Future.successful(())
    } else if (value == BuildServer || value == All) {
      scribe.info("Resetting build server selection.")
      switchBspServer()
    } else {
      Future.successful(())
    }
    result.foreach(_ => executeRefreshDoctor())
    result.ignoreValue
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
          new MessageActionItem(PopupChoiceReset.BuildImport),
          new MessageActionItem(PopupChoiceReset.BuildServer),
        ).asJava
      )
      params
    }
    val all = new MessageActionItem(PopupChoiceReset.All)

    languageClient
      .showMessageRequest(
        choicesParams(),
        defaultTo = () => {
          all
        },
      )
      .asScala
      .flatMap { item =>
        if (item == null) {
          Future.successful(())
        } else if (item.getTitle() == PopupChoiceReset.BuildTool) {
          reset(PopupChoiceReset.BuildTool)
        } else if (item.getTitle() == PopupChoiceReset.BuildImport) {
          reset(PopupChoiceReset.BuildImport)
        } else if (item.getTitle() == PopupChoiceReset.BuildServer) {
          reset(PopupChoiceReset.BuildServer)
        } else if (item.getTitle() == PopupChoiceReset.All) {
          reset(PopupChoiceReset.All)
        } else {
          Future.successful(())
        }
      }
  }
}

object PopupChoiceReset {
  final val BuildTool = "Build tool selection"
  final val BuildImport = "Build import choice"
  final val BuildServer = "Build server selection"
  final val All = "All choices"
}
