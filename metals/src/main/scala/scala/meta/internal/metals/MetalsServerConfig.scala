package scala.meta.internal.metals

import scala.meta.internal.metals.Configs._
import scala.meta.internal.pc.PresentationCompilerConfigImpl
import scala.meta.pc.PresentationCompilerConfig.OverrideDefFormat

/**
 * Configuration parameters for the Metals language server.
 *
 * @param bloopProtocol the protocol to communicate with Bloop.
 * @param fileWatcher whether to start an embedded file watcher in case the editor
 *                    does not support file watching.
 * @param statusBar how to handle metals/status notifications.
 * @param doctorFormat the format that you'd like doctor to return
 * @param slowTask how to handle metals/slowTask requests.
 * @param snippetAutoIndent if the client defaults to adding the identation of the reference
 *                          line that the operation started on (relevant for multiline textEdits)
 * @param isHttpEnabled whether to start the Metals HTTP client interface. This is needed
 *                      for clients with limited support for UI dialogues
 *                      that don't implement window/showMessageRequest yet.
 * @param icons what icon set to use for messages.
 */
final case class MetalsServerConfig(
    globSyntax: GlobSyntaxConfig = GlobSyntaxConfig.default,
    statusBar: StatusBarConfig = StatusBarConfig.default,
    slowTask: SlowTaskConfig = SlowTaskConfig.default,
    doctorFormat: DoctorFormatConfig = DoctorFormatConfig.default,
    executeClientCommand: ExecuteClientCommandConfig =
      ExecuteClientCommandConfig.default,
    snippetAutoIndent: Boolean = MetalsServerConfig.binaryOption(
      "metals.snippet-auto-indent",
      default = true
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
    openFilesOnRenames: Boolean = false,
    renameFileThreshold: Int = 300,
    bloopEmbeddedVersion: String = System.getProperty(
      "bloop.embedded.version",
      BuildInfo.bloopVersion
    ),
    icons: Icons = Icons.default,
    statistics: StatisticsConfig = StatisticsConfig.default,
    compilers: PresentationCompilerConfigImpl = CompilersConfig()
) {
  override def toString: String =
    List[String](
      s"glob-syntax=$globSyntax",
      s"status-bar=$statusBar",
      s"open-files-on-rename=$openFilesOnRenames",
      s"rename-file-threshold=$renameFileThreshold",
      s"slow-task=$slowTask",
      s"execute-client-command=$executeClientCommand",
      s"compilers=$compilers",
      s"http=$isHttpEnabled",
      s"input-box=$isInputBoxEnabled",
      s"icons=$icons",
      s"statistics=$statistics",
      s"doctor-format=$doctorFormat"
    ).mkString("MetalsServerConfig(\n  ", ",\n  ", "\n)")
}
object MetalsServerConfig {
  def isTesting: Boolean = "true" == System.getProperty("metals.testing")
  def binaryOption(key: String, default: Boolean): Boolean =
    sys.props.get(key) match {
      case Some("true" | "on") => true
      case Some("false" | "off") => false
      case Some(other) =>
        scribe.warn(
          s"Invalid value for property '$key', required 'true|on' or 'false|off', but got '$other'. Using the default value '$default'"
        )
        default
      case None => default
    }

  def base: MetalsServerConfig = MetalsServerConfig()
  def default: MetalsServerConfig = {
    System.getProperty("metals.client", "default") match {
      case "vscode" =>
        base.copy(
          icons = Icons.vscode,
          openFilesOnRenames = true,
          globSyntax = GlobSyntaxConfig.vscode,
          compilers = base.compilers.copy(
            _parameterHintsCommand = Some("editor.action.triggerParameterHints"),
            _completionCommand = Some("editor.action.triggerSuggest"),
            overrideDefFormat = OverrideDefFormat.Unicode
          )
        )
      case "vim-lsc" =>
        base.copy(
          // window/logMessage output is always visible and non-invasive in vim-lsc
          statusBar = StatusBarConfig.logMessage,
          isHttpEnabled = true,
          icons = Icons.unicode,
          compilers = base.compilers.copy(
            snippetAutoIndent = false
          )
        )
      case "coc.nvim" =>
        base.copy(
          statusBar = StatusBarConfig.showMessage,
          isHttpEnabled = true,
          compilers = base.compilers.copy(
            _parameterHintsCommand = Some("editor.action.triggerParameterHints"),
            _completionCommand = Some("editor.action.triggerSuggest"),
            overrideDefFormat = OverrideDefFormat.Unicode,
            isCompletionItemResolve = false
          )
        )
      case "coc-metals" =>
        base.copy(
          compilers = base.compilers.copy(
            _parameterHintsCommand = Some("editor.action.triggerParameterHints"),
            _completionCommand = Some("editor.action.triggerSuggest"),
            overrideDefFormat = OverrideDefFormat.Unicode,
            isCompletionItemResolve = false
          )
        )
      case "sublime" =>
        base.copy(
          isHttpEnabled = true,
          statusBar = StatusBarConfig.showMessage,
          icons = Icons.unicode,
          isExitOnShutdown = true,
          compilers = base.compilers.copy(
            // Avoid showing the method signature twice because it's already visible in the label.
            isCompletionItemDetailEnabled = false
          )
        )
      case "emacs" =>
        base.copy(
          executeClientCommand = ExecuteClientCommandConfig.on,
          compilers = base.compilers.copy(
            snippetAutoIndent = false
          )
        )
      case _ =>
        base
    }
  }
}
