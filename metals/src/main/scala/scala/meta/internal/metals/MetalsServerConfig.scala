package scala.meta.internal.metals

import scala.meta.internal.metals.Configs._

/**
 * Configuration parameters for the Metals language server.
 *
 * @param bloopProtocol the protocol to communicate with Bloop.
 * @param fileWatcher whether to start an embedded file watcher in case the editor
 *                    does not support file watching.
 * @param statusBar how to handle metals/status notifications.
 * @param slowTask how to handle metals/slowTask requests.
 * @param showMessage how to handle window/showMessage notifications.
 * @param showMessageRequest how to handle window/showMessageRequest requests.
 * @param isNoInitialized set true if the editor client doesn't call the `initialized`
 *                        notification for some reason, see https://github.com/natebosch/vim-lsc/issues/113
 * @param isHttpEnabled whether to start the Metals HTTP client interface. This is needed
 *                      for clients with limited support for UI dialogues like Sublime Text
 *                      that don't implement window/showMessageRequest yet.
 * @param icons what icon set to use for messages.
 */
final case class MetalsServerConfig(
    bloopProtocol: BloopProtocol = BloopProtocol.default,
    globSyntax: GlobSyntaxConfig = GlobSyntaxConfig.default,
    statusBar: StatusBarConfig = StatusBarConfig.default,
    slowTask: SlowTaskConfig = SlowTaskConfig.default,
    executeClientCommand: ExecuteClientCommandConfig =
      ExecuteClientCommandConfig.default,
    showMessage: ShowMessageConfig = ShowMessageConfig.default,
    showMessageRequest: ShowMessageRequestConfig =
      ShowMessageRequestConfig.default,
    isNoInitialized: Boolean = MetalsServerConfig.binaryOption(
      "metals.no-initialized",
      default = false
    ),
    isExitOnShutdown: Boolean = MetalsServerConfig.binaryOption(
      "metals.exit-on-shutdown",
      default = false
    ),
    isHttpEnabled: Boolean = MetalsServerConfig.binaryOption(
      "metals.http",
      default = false
    ),
    isInputBoxEnabled: Boolean = MetalsServerConfig.binaryOption(
      "metals.input-box",
      default = false
    ),
    isVerbose: Boolean = MetalsServerConfig.binaryOption(
      "metals.verbose",
      default = false
    ),
    isAutoServer: Boolean = MetalsServerConfig.binaryOption(
      "metals.h2.auto-server",
      default = true
    ),
    isWarningsEnabled: Boolean = MetalsServerConfig.binaryOption(
      "metals.warnings",
      default = true
    ),
    icons: Icons = Icons.default,
    statistics: StatisticsConfig = StatisticsConfig.default
) {
  override def toString: String =
    List[String](
      s"bloop-protocol=$bloopProtocol",
      s"glob-syntax=$globSyntax",
      s"status-bar=$statusBar",
      s"slow-task=$slowTask",
      s"execute-client-command=$executeClientCommand",
      s"show-message=$showMessage",
      s"show-message-request=$showMessageRequest",
      s"no-initialized=$isNoInitialized",
      s"http=$isHttpEnabled",
      s"input-box=$isInputBoxEnabled",
      s"icons=$icons",
      s"statistics=$statistics"
    ).mkString("MetalsServerConfig(\n  ", ",\n  ", "\n)")
}
object MetalsServerConfig {
  def isTesting: Boolean = "true" == System.getProperty("metals.testing")
  private def binaryOption(key: String, default: Boolean): Boolean =
    System.getProperty(key) match {
      case "true" | "on" => true
      case "false" | "off" => false
      case _ => default
    }

  def base: MetalsServerConfig = MetalsServerConfig()
  def default: MetalsServerConfig = {
    System.getProperty("metals.client", "default") match {
      case "vscode" =>
        base.copy(
          statusBar = StatusBarConfig.on,
          slowTask = SlowTaskConfig.on,
          icons = Icons.vscode,
          executeClientCommand = ExecuteClientCommandConfig.on,
          globSyntax = GlobSyntaxConfig.vscode,
          isInputBoxEnabled = true
        )
      case "vim-lsc" =>
        base.copy(
          // window/logMessage output is always visible and non-invasive in vim-lsc
          statusBar = StatusBarConfig.logMessage,
          // Not strictly needed, but helpful while this integration matures.
          isHttpEnabled = true,
          icons = Icons.unicode
        )
      case "sublime" =>
        base.copy(
          isHttpEnabled = true,
          // Sublime text opens an invasive alert dialogue for window/showMessage
          // and window/showMessageRequest.
          showMessage = ShowMessageConfig.logMessage,
          showMessageRequest = ShowMessageRequestConfig.on,
          icons = Icons.unicode,
          isExitOnShutdown = true
        )
      case "emacs" =>
        base.copy(
          isHttpEnabled = true
        )
      case _ =>
        base
    }
  }
}
