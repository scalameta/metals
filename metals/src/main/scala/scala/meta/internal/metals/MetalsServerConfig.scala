package scala.meta.internal.metals

case class MetalsServerConfig(
    bloopProtocol: BloopProtocol = BloopProtocol.Auto,
    isExtensionsEnabled: Boolean =
      System.getProperty("metals.extensions") == "true",
    fileWatcher: FileWatcherConfig = FileWatcherConfig.default,
    isLogStatusBar: Boolean =
      System.getProperty("metals.log-status") == "true",
    isNoInitialized: Boolean =
      System.getProperty("metals.no-initialized") == "true",
    isHttpEnabled: Boolean =
      System.getProperty("metals.http") == "true",
    isLogShowMessageRequest: Boolean =
      System.getProperty("metals.log-show-message-request") == "true",
    isLogShowMessage: Boolean =
      System.getProperty("metals.log-show-message") == "true",
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
    System.getProperty("metals.client", "unknown") match {
      case "vim-lsc" =>
        MetalsServerConfig().copy(
          isExtensionsEnabled = false,
          fileWatcher = FileWatcherConfig.auto,
          isLogStatusBar = true,
          isNoInitialized = true,
          // Not strictly needed, but helpful while this integration matures.
          isHttpEnabled = true,
          icons = Icons.unicode
        )
      case "sublime" =>
        MetalsServerConfig().copy(
          isExtensionsEnabled = false,
          fileWatcher = FileWatcherConfig.auto,
          isLogStatusBar = false,
          isNoInitialized = true,
          isHttpEnabled = true,
          // Sublime text opens an invasive alert dialogue for window/showMessage
          // and window/showMessageRequest.
          isLogShowMessage = true,
          isLogShowMessageRequest = true,
          icons = Icons.unicode
        )
      case _ =>
        MetalsServerConfig()
    }
  }
}
