package scala.meta.internal.metals

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.bsp.BspConnector
import scala.meta.internal.bsp.BuildChange
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.ShowMessageRequestParams

class PopupChoiceReset(
    workspace: AbsolutePath,
    tables: Tables,
    languageClient: MetalsLanguageClient,
    doctor: Doctor,
    slowConnect: () => Future[BuildChange],
    bspConnector: BspConnector,
    quickConnect: () => Future[BuildChange]
) {
  import PopupChoiceReset._

  def reset(value: String)(implicit ec: ExecutionContext): Future[Unit] = {
    val result = if (value == BuildTool) {
      scribe.info("Resetting build tool selection.")
      tables.buildTool.reset()
      slowConnect().ignoreValue
    } else if (value == BuildImport) {
      tables.dismissedNotifications.ImportChanges.reset()
      Future.successful(())
    } else if (value == BuildServer) {
      scribe.info("Resetting build server selection.")
      (for {
        didChange <- bspConnector.switchBuildServer(
          workspace,
          slowConnect
        )
        if didChange
      } yield quickConnect()).ignoreValue
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
          new MessageActionItem(PopupChoiceReset.BuildImport),
          new MessageActionItem(PopupChoiceReset.BuildServer)
        ).asJava
      )
      params
    }

    languageClient
      .showMessageRequest(choicesParams())
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
        } else {
          Future.successful(())
        }
      }
  }
}

object PopupChoiceReset {
  final val BuildTool = "Build tool selection"
  final val BuildImport = "Build import"
  final val BuildServer = "Build server selection"
}
