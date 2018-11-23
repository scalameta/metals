package scala.meta.internal.metals

case class MetalsServerConfig(
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
      s"http=$isHttpEnabled"
    ).mkString("MetalsServerConfig(", ",", ")")
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
