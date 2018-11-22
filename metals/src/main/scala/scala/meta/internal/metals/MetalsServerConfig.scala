package scala.meta.internal.metals

case class MetalsServerConfig(
    bloopProtocol: BloopProtocol = BloopProtocol.default,
    isExtensionsEnabled: Boolean =
      System.getProperty("metals.extensions") == "true",
    fileWatcher: FileWatcherConfig = FileWatcherConfig.default,
    statusBar: StatusBarConfig = StatusBarConfig.default,
    slowTask: SlowTaskConfig = SlowTaskConfig.default,
    showMessage: ShowMessageConfig = ShowMessageConfig.default,
    showMessageRequest: ShowMessageRequestConfig =
      ShowMessageRequestConfig.default,
    isNoInitialized: Boolean =
      System.getProperty("metals.no-initialized") == "true",
    isHttpEnabled: Boolean =
      System.getProperty("metals.http") == "on",
    icons: Icons = Icons.default
) {
  override def toString: String =
    List[String](
      s"bloop-protocol=$bloopProtocol",
      s"extensions=$isExtensionsEnabled",
      s"file-watcher=$fileWatcher",
      s"http=$isHttpEnabled"
    ).mkString("MetalsServerConfig(", ",", ")")
}
object MetalsServerConfig {
  def default: MetalsServerConfig = {
    System.getProperty("metals.client", "default") match {
      case "vim-lsc" =>
        MetalsServerConfig().copy(
          isExtensionsEnabled = false,
          fileWatcher = FileWatcherConfig.auto,
          isNoInitialized = true,
          // Not strictly needed, but helpful while this integration matures.
          isHttpEnabled = true,
          icons = Icons.unicode
        )
      case "sublime" =>
        MetalsServerConfig().copy(
          isExtensionsEnabled = false,
          fileWatcher = FileWatcherConfig.auto,
          isNoInitialized = true,
          isHttpEnabled = true,
          // Sublime text opens an invasive alert dialogue for window/showMessage
          // and window/showMessageRequest.
          icons = Icons.unicode
        )
      case _ =>
        MetalsServerConfig()
    }
  }
}
