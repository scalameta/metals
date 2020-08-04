package scala.meta.internal.metals

import scala.meta.internal.metals.Configs._
import scala.meta.internal.pc.PresentationCompilerConfigImpl
import scala.meta.pc.PresentationCompilerConfig.OverrideDefFormat

/**
 * Configuration parameters for the Metals language server.
 * While these can be used to configure Metals, it's preferable
 * that instead you configure Metals via `InitializationOptions`.
 *
 * @param globSyntax pattern used for `DidChangeWatchedFilesRegistrationOptions`.
 * @param statusBar how to handle metals/status notifications.
 * @param slowTask how to handle metals/slowTask requests.
 * @param executeClientCommand whether client provides the ability to support the
 *                             `metals/executeClientCommand` command.
 * @param snippetAutoIndent if the client defaults to adding the identation of
 *                          the reference line that the operation started on
 *                          (relevant for multiline textEdits)
 * @param isExitOnShutdown whether the client needs Metals to shut down manually on exit.
 * @param isHttpEnabled whether to start the Metals HTTP client interface.
 *                      This is needed for clients with limited support for UI
 *                      dialogues that don't implement window/showMessageRequest yet.
 * @param isInputBoxEnabled whether the client supports the `metals/inputBox` extension.
 * @param isVerbose turn on verbose logging.
 * @param isAutoServer whether or not `AUTO_SERVER` should be set for h2.
 * `*                    http://www.h2database.com/html/features.html#auto_mixed_mode
 * @param remoteTimeout timeout period for retrieving references while using `RemoteLanguageServer`.
 * @param openFilesOnRenames whether or not file should be opened when a rename occurs
 *                           in an unopened file.
 * @param renameFileThreshold amount of files that should be opened during a rename
 *                            if the `openFilesOnRenames` is enabled.
 * @param askToReconnect whether the user should be prompted to reconnect after a
 *                       BuildServer connection is lost.
 * @param icons what icon set to use for messages.
 * @param statistics if all statistics in Metals should be enabled.
 * @param compilers configuration for the `PresentationCompilerConfig`.
 * @param allowMultilineStringFormatting whether or not `multilineStringFormatting` should
 *                                       be turned off. By default this is on, but Metals only
 *                                       supports a small subset of this, so it may be problematic
 *                                       for certain clients.
 */
final case class MetalsServerConfig(
    globSyntax: GlobSyntaxConfig = GlobSyntaxConfig.default,
    statusBar: StatusBarConfig = StatusBarConfig.default,
    slowTask: SlowTaskConfig = SlowTaskConfig.default,
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
    isCommandInHtmlSupported: Boolean = MetalsServerConfig.binaryOption(
      "metals.commands-in-html",
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
    remoteTimeout: String = System.getProperty(
      "metals.timeout",
      "1 minute"
    ),
    openFilesOnRenames: Boolean = false,
    renameFileThreshold: Int = 300,
    askToReconnect: Boolean = MetalsServerConfig.binaryOption(
      "metals.ask-to-reconnect",
      default = false
    ),
    icons: Icons = Icons.fromString(System.getProperty("metals.icons")),
    statistics: StatisticsConfig = StatisticsConfig.default,
    compilers: PresentationCompilerConfigImpl = CompilersConfig(),
    allowMultilineStringFormatting: Boolean = MetalsServerConfig.binaryOption(
      "metals.allow-multiline-string-formatting",
      default = true
    )
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
      s"ask-to-reconnect=$askToReconnect",
      s"icons=$icons",
      s"statistics=$statistics"
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

  val metalsClientType: Option[String] = Option(
    System.getProperty("metals.client")
  )

  def base: MetalsServerConfig = MetalsServerConfig()
  def default: MetalsServerConfig = {
    metalsClientType.getOrElse("default") match {
      case "vscode" =>
        base.copy(
          icons = Icons.vscode,
          globSyntax = GlobSyntaxConfig.vscode,
          isCommandInHtmlSupported = true,
          compilers = base.compilers.copy(
            _parameterHintsCommand =
              Some("editor.action.triggerParameterHints"),
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
            _parameterHintsCommand =
              Some("editor.action.triggerParameterHints"),
            _completionCommand = Some("editor.action.triggerSuggest"),
            overrideDefFormat = OverrideDefFormat.Unicode,
            isCompletionItemResolve = false
          )
        )
      case "coc-metals" =>
        base.copy(
          compilers = base.compilers.copy(
            _parameterHintsCommand =
              Some("editor.action.triggerParameterHints"),
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
