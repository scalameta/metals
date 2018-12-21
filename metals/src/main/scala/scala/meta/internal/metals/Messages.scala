package scala.meta.internal.metals

import ch.epfl.scala.bsp4j.BspConnectionDetails
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.ShowMessageRequestParams
import scala.collection.JavaConverters._
import scala.collection.mutable
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
  def notNow: MessageActionItem =
    new MessageActionItem("Not now")
  object ImportBuildChanges {
    def yes: MessageActionItem =
      new MessageActionItem("Import changes")
    def params: ShowMessageRequestParams = {
      val params = new ShowMessageRequestParams()
      params.setMessage("sbt build needs to be re-imported")
      params.setType(MessageType.Info)
      params.setActions(
        List(
          yes,
          notNow,
          dontShowAgain
        ).asJava
      )
      params
    }
  }

  object ImportBuild {
    def yes = new MessageActionItem("Import build")
    def params: ShowMessageRequestParams = {
      val params = new ShowMessageRequestParams()
      params.setMessage(
        "New sbt workspace detected, would you like to import the build?"
      )
      params.setType(MessageType.Info)
      params.setActions(
        List(
          yes,
          notNow,
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

  object CheckDoctor {
    def problemsFixed: MessageParams =
      new MessageParams(
        MessageType.Info,
        "Build is correctly configured now, navigation will work for all build targets."
      )
    def moreInfo: String =
      " Select 'More information' to learn how to fix this problem.."
    def allProjectsMisconfigured: String =
      "Navigation will not work for this build due to mis-configuration." + moreInfo
    def singleMisconfiguredProject(name: String): String =
      s"Navigation will not work in project '$name' due to mis-configuration." + moreInfo
    def multipleMisconfiguredProjects(count: Int): String =
      s"Code navigation will not work for $count build targets in this workspace due to mis-configuration. " + moreInfo
    def isDoctor(params: ShowMessageRequestParams): Boolean =
      params.getActions.asScala.contains(moreInformation)
    def moreInformation: MessageActionItem =
      new MessageActionItem("More information")
    def dismissForever: MessageActionItem =
      new MessageActionItem("Don't show again")
    def params(problem: String): ShowMessageRequestParams = {
      val params = new ShowMessageRequestParams()
      params.setMessage(problem)
      params.setType(MessageType.Warning)
      params.setActions(
        List(
          moreInformation,
          dismissForever
        ).asJava
      )
      params
    }
  }

  object IncompatibleSbtVersion {
    def statusBar(sbt: Sbt) = MetalsStatusParams(
      s"$$(alert) Manual build import required",
      tooltip = toFixMessage,
      command = ServerCommands.OpenBrowser(learnMoreUrl)
    )
    def toFixMessage =
      "To fix this problem, upgrade to sbt v0.13.17+ or v1.2.1+ or manually import the build."
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

  object SelectBspServer {
    case class Request(
        params: ShowMessageRequestParams,
        details: Map[String, BspConnectionDetails]
    )
    def message: String =
      "Multiple build servers detected, which one do you want to use?"
    def isSelectBspServer(params: ShowMessageRequestParams): Boolean =
      params.getMessage == message
    def request(
        candidates: List[BspConnectionDetails]
    ): Request = {
      val params = new ShowMessageRequestParams()
      params.setMessage(message)
      params.setType(MessageType.Warning)
      val details = mutable.Map.empty[String, BspConnectionDetails]
      // The logic for choosing item names is a bit tricky because we want
      // the following characteristics:
      // - all options must be unique, we get a title string back from the
      //   editor for which server the user chose.
      // - happy path: title is build server name without noisy version number.
      // - name conflicts: disambiguate conflicting names by version number
      // - name+version conflicts: append random characters to the title.
      val items = candidates.map { candidate =>
        val nameConflicts = candidates.count(_.getName == candidate.getName)
        val title: String = if (nameConflicts < 2) {
          candidate.getName
        } else {
          val versionConflicts = candidates.count { c =>
            c.getName == candidate.getName &&
            c.getVersion == candidate.getVersion
          }
          if (versionConflicts < 2) {
            s"${candidate.getName} v${candidate.getVersion}"
          } else {
            val stream = Stream.from(0).map { i =>
              val ch = ('a'.toInt + i).toChar
              s"${candidate.getName} v${candidate.getVersion} ($ch)"
            }
            stream.find(!details.contains(_)).get
          }
        }
        details(title) = candidate
        new MessageActionItem(title)
      }
      params.setActions(items.asJava)
      Request(params, details.toMap)
    }
  }

  object BspSwitch {
    def noInstalledServer: MessageParams =
      new MessageParams(
        MessageType.Error,
        "Unable to switch build server since there are no installed build servers on this computer. " +
          "To fix this problem, install a build server first."
      )
    def onlyOneServer(name: String): MessageParams =
      new MessageParams(
        MessageType.Warning,
        s"Unable to switch build server since there is only one installed build server '$name' on this computer."
      )
  }
}
