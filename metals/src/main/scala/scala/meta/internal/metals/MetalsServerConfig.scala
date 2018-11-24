package scala.meta.internal.metals

/**
 * Configuration parameters for the Metals language server.
 *
 * @param bloopProtocol the protocol to communicate with Bloop.
 * @param fileWatcher whether to start a embedded file watcher in case the editor
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
    fileWatcher: FileWatcherConfig = FileWatcherConfig.default,
    statusBar: StatusBarConfig = StatusBarConfig.default,
    slowTask: SlowTaskConfig = SlowTaskConfig.default,
    showMessage: ShowMessageConfig = ShowMessageConfig.default,
    showMessageRequest: ShowMessageRequestConfig =
      ShowMessageRequestConfig.default,
    isNoInitialized: Boolean =
      System.getProperty("metals.no-initialized") == "true",
    isHttpEnabled: Boolean =
      System.getProperty("metals.http") == "true",
    icons: Icons = Icons.default
) {
  override def toString: String =
    List[String](
      s"bloop-protocol=$bloopProtocol",
      s"file-watcher=$fileWatcher",
      s"status-bar=$statusBar",
      s"slow-task=$slowTask",
      s"show-message=$showMessage",
      s"show-message-request=$showMessageRequest",
      s"no-initialized=$isNoInitialized",
      s"http=$isHttpEnabled",
      s"icons=$icons"
    ).mkString("MetalsServerConfig(\n  ", ",\n  ", "\n)")
}
object MetalsServerConfig {
  def default: MetalsServerConfig = {
    System.getProperty("metals.client", "default") match {
      case "vscode" =>
        MetalsServerConfig().copy(
          statusBar = StatusBarConfig.on,
          slowTask = SlowTaskConfig.on,
          icons = Icons.octicons
        )
      case "vim-lsc" =>
        MetalsServerConfig().copy(
          fileWatcher = FileWatcherConfig.auto,
          // window/logMessage output is always visible and non-invasive in vim-lsc
          statusBar = StatusBarConfig.logMessage,
          isNoInitialized = true,
          // Not strictly needed, but helpful while this integration matures.
          isHttpEnabled = true,
          icons = Icons.unicode
        )
      case "sublime" =>
        MetalsServerConfig().copy(
          fileWatcher = FileWatcherConfig.auto,
          isNoInitialized = true,
          isHttpEnabled = true,
          // Sublime text opens an invasive alert dialogue for window/showMessage
          // and window/showMessageRequest.
          showMessage = ShowMessageConfig.logMessage,
          showMessageRequest = ShowMessageRequestConfig.logMessage,
          icons = Icons.unicode
        )
      case _ =>
        MetalsServerConfig()
    }
  }
}
