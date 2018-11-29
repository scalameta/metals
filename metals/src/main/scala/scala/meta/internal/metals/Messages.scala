package scala.meta.internal.metals

import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.ShowMessageRequestParams
import scala.collection.JavaConverters._
import scala.meta.internal.metals.BuildTool.Sbt

/**
 * Constants for requests/dialogues via LSP window/showMessage and window/showMessageRequest.
 */
object Messages extends Messages(Icons.vscode)

class Messages(icons: Icons) {
  val BloopInstallProgress = MetalsSlowTaskParams("sbt bloopInstall")
  val ImportProjectFailed = new MessageParams(
    MessageType.Error,
    "Import project failed, no functionality will work. See the logs for more details"
  )
  val ImportProjectPartiallyFailed = new MessageParams(
    MessageType.Warning,
    "Import project partially failed, limited functionality may work in some parts of the workspace. " +
      "See the logs for more details. "
  )

  def dontShowAgain: MessageActionItem =
    new MessageActionItem("Don't show again")
  object ReimportSbtProject {
    def yes: MessageActionItem =
      new MessageActionItem("Import changes")
    def params: ShowMessageRequestParams = {
      val params = new ShowMessageRequestParams()
      params.setMessage("sbt projects need to be imported")
      params.setType(MessageType.Info)
      params.setActions(
        List(
          yes,
          dontShowAgain
        ).asJava
      )
      params
    }
  }

  object ImportBuildViaBloop {
    def yes = new MessageActionItem("Import build via Bloop")
    def params: ShowMessageRequestParams = {
      val params = new ShowMessageRequestParams()
      params.setMessage(
        "sbt build detected, would you like to import via Bloop?"
      )
      params.setType(MessageType.Info)
      params.setActions(
        List(
          yes,
          dontShowAgain
        ).asJava
      )
      params
    }

  }

  val PartialNavigation = MetalsStatusParams(
    "$(info) Partial navigation",
    tooltip =
      "To fix this problem, update your build settings to use the same compiler plugins and compiler settings as the external library.",
    command = ClientCommands.FocusDiagnostics.id
  )

  object IncompatibleSbtVersion {
    def statusBar(sbt: Sbt) = MetalsStatusParams(
      s"$$(alert) Manual build import required",
      tooltip = toFixMessage,
      command = ServerCommands.OpenBrowser(learnMoreUrl)
    )
    def toFixMessage =
      "To fix this problem, upgrade to sbt v1.2.1+ or manually import the build."
    def dismissForever: MessageActionItem =
      new MessageActionItem("Don't show again")
    def learnMore: MessageActionItem =
      new MessageActionItem("Learn more")
    def learnMoreUrl: String = Urls.docs("import-build")
    def params(sbt: Sbt): ShowMessageRequestParams = {
      val params = new ShowMessageRequestParams()
      params.setMessage(
        s"Automatic build import is not supported for sbt ${sbt.version}. $toFixMessage"
      )
      params.setType(MessageType.Warning)
      params.setActions(
        List(
          learnMore,
          dismissForever
        ).asJava
      )
      params
    }
  }

  object Only212Navigation {
    def statusBar(scalaVersion: String) =
      MetalsStatusParams(
        "$(alert) No navigation",
        tooltip = params(scalaVersion).getMessage
      )
    def dismissForever: MessageActionItem =
      new MessageActionItem("Don't show again")
    def ok: MessageActionItem =
      new MessageActionItem("Ok")
    def params(scalaVersion: String): ShowMessageRequestParams = {
      val params = new ShowMessageRequestParams()
      params.setMessage(
        s"Navigation for external library sources is not supported in Scala $scalaVersion."
      )
      params.setType(MessageType.Warning)
      params.setActions(
        List(
          ok,
          dismissForever
        ).asJava
      )
      params
    }
  }

}
