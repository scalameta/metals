package scala.meta.internal.metals

import scala.concurrent.duration.Duration
import scala.util.Try

import scala.meta.internal.metals.Configs._
import scala.meta.internal.pc.PresentationCompilerConfigImpl
import scala.meta.pc.PresentationCompilerConfig.OverrideDefFormat

/**
 * Configuration parameters for the Metals language server.
 * While these can be used to configure Metals, it's preferable
 * that instead you configure Metals via `InitializationOptions`.
 *
 * @param globSyntax pattern used for `DidChangeWatchedFilesRegistrationOptions`.
 * @param statusBar how to handle metals/status notifications with {"statusType": "metals"}.
 * @param bspStatusBar how to handle metals/status notifications with {"statusType": "bsp"}.
 * @param moduleStatusBar how to handle metals/status notifications with {"statusType": "module"}.
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
 * @param openFilesOnRenames whether or not file should be opened when a rename occurs
 *                           in an unopened file.
 * @param renameFileThreshold amount of files that should be opened during a rename
 *                            if the `openFilesOnRenames` is enabled.
 * @param askToReconnect whether the user should be prompted to reconnect after a
 *                       BuildServer connection is lost.
 * @param askToRestartBloop whether the user should be prompted to restart the Bloop
 *                          server when version changes. If false, Bloop will be
 *                          restarted automatically.
 * @param icons what icon set to use for messages.
 * @param statistics if all statistics in Metals should be enabled.
 * @param compilers configuration for the `PresentationCompilerConfig`.
 * @param allowMultilineStringFormatting whether or not `multilineStringFormatting` should
 *                                       be turned off. By default this is on, but Metals only
 *                                       supports a small subset of this, so it may be problematic
 *                                       for certain clients.
 * @param macOsMaxWatchRoots The maximum number of root directories to watch on MacOS.
 * @param maxLogFileSize The maximum size of the log file before it gets backed up and truncated.
 * @param maxLogBackups The maximum number of backup log files.
 * @param metalsToIdleTime The time that needs to pass with no action to consider metals as idle.
 * @param pingInterval Interval in which we ping the build server.
 */
final case class MetalsServerConfig(
    globSyntax: GlobSyntaxConfig = GlobSyntaxConfig.default,
    statusBar: StatusBarConfig = StatusBarConfig.default,
    bspStatusBar: StatusBarConfig = StatusBarConfig.bspDefault,
    moduleStatusBar: StatusBarConfig = StatusBarConfig.moduleDefault,
    executeClientCommand: ExecuteClientCommandConfig =
      ExecuteClientCommandConfig.default,
    snippetAutoIndent: Boolean = MetalsServerConfig.binaryOption(
      "metals.snippet-auto-indent",
      default = true,
    ),
    isExitOnShutdown: Boolean = MetalsServerConfig.binaryOption(
      "metals.exit-on-shutdown",
      default = false,
    ),
    isHttpEnabled: Boolean = MetalsServerConfig.binaryOption(
      "metals.http",
      default = false,
    ),
    isInputBoxEnabled: Boolean = MetalsServerConfig.binaryOption(
      "metals.input-box",
      default = false,
    ),
    isVerbose: Boolean = MetalsServerConfig.binaryOption(
      "metals.verbose",
      default = false,
    ),
    openFilesOnRenames: Boolean = false,
    renameFileThreshold: Int = 300,
    askToReconnect: Boolean = MetalsServerConfig.binaryOption(
      "metals.ask-to-reconnect",
      default = false,
    ),
    askToRestartBloop: Boolean = MetalsServerConfig.binaryOption(
      "metals.ask-to-restart-bloop",
      default = false,
    ),
    icons: Icons = Icons.fromString(System.getProperty("metals.icons")),
    statistics: StatisticsConfig = StatisticsConfig.default,
    compilers: PresentationCompilerConfigImpl = CompilersConfig(),
    allowMultilineStringFormatting: Boolean = MetalsServerConfig.binaryOption(
      "metals.allow-multiline-string-formatting",
      default = true,
    ),
    macOsMaxWatchRoots: Int =
      Option(System.getProperty("metals.macos-max-watch-roots"))
        .filter(_.forall(Character.isDigit(_)))
        .map(_.toInt)
        .getOrElse(32),
    loglevel: String =
      sys.props.get("metals.loglevel").map(_.toLowerCase()).getOrElse("info"),
    maxLogFileSize: Long = Option(System.getProperty("metals.max-logfile-size"))
      .withFilter(_.forall(Character.isDigit(_)))
      .map(_.toLong)
      .getOrElse(3 << 20),
    maxLogBackups: Int = Option(System.getProperty("metals.max-log-backups"))
      .withFilter(_.forall(Character.isDigit(_)))
      .map(_.toInt)
      .getOrElse(10),
    metalsToIdleTime: Duration =
      Option(System.getProperty("metals.server-to-idle-time"))
        .flatMap(opt => Try(Duration(opt)).toOption)
        .getOrElse(Duration("10m")),
    pingInterval: Duration =
      Option(System.getProperty("metals.build-server-ping-interval"))
        .flatMap(opt => Try(Duration(opt)).toOption)
        .getOrElse(Duration("1m")),
    worksheetTimeout: Int =
      Option(System.getProperty("metals.worksheet-timeout"))
        .filter(_.forall(Character.isDigit(_)))
        .map(_.toInt)
        .getOrElse(30),
    debugServerStartTimeout: Int =
      Option(System.getProperty("metals.debug-server-start-timeout"))
        .filter(_.forall(Character.isDigit(_)))
        .map(_.toInt)
        .getOrElse(60),
    enableBestEffort: Boolean = MetalsServerConfig.binaryOption(
      "metals.enable-best-effort",
      default = false,
    ),
    foldingRageMinimumSpan: Int =
      Option(System.getProperty("metals.folding-range-minimum-span"))
        .filter(_.forall(Character.isDigit(_)))
        .map(_.toInt)
        .getOrElse(3),
    disableShowMessageRequest: Boolean = MetalsServerConfig.binaryOption(
      "metals.disable-show-message-request",
      default = false,
    ),
) {
  override def toString: String =
    List[String](
      s"glob-syntax=$globSyntax",
      s"status-bar=$statusBar",
      s"open-files-on-rename=$openFilesOnRenames",
      s"rename-file-threshold=$renameFileThreshold",
      s"execute-client-command=$executeClientCommand",
      s"compilers=$compilers",
      s"http=$isHttpEnabled",
      s"input-box=$isInputBoxEnabled",
      s"ask-to-reconnect=$askToReconnect",
      s"ask-to-restart-bloop=$askToRestartBloop",
      s"icons=$icons",
      s"statistics=$statistics",
      s"macos-max-watch-roots=${macOsMaxWatchRoots}",
      s"loglevel=${loglevel}",
      s"max-logfile-size=${maxLogFileSize}",
      s"max-log-backup=${maxLogBackups}",
      s"server-to-idle-time=${metalsToIdleTime}",
      s"build-server-ping-interval=${pingInterval}",
      s"worksheet-timeout=$worksheetTimeout",
      s"debug-server-start-timeout=$debugServerStartTimeout",
      s"enable-best-effort=$enableBestEffort",
      s"folding-range-minimum-span=$foldingRageMinimumSpan",
      s"disable-show-message-request=$disableShowMessageRequest",
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

  object MetalsClientType {
    val vscode = "vscode"
    val vimLsc = "vim-lsc"
    val cocNvim = "coc.nvim"
    val cocMetals = "coc-metals"
    val sublime = "sublime"
    val emacs = "emacs"
    val helix = "helix"
  }

  def base: MetalsServerConfig = MetalsServerConfig()
  def default: MetalsServerConfig = {
    metalsClientType.getOrElse("default") match {
      case MetalsClientType.vscode =>
        base.copy(
          icons = Icons.vscode,
          globSyntax = GlobSyntaxConfig.vscode,
          compilers = base.compilers.copy(
            _parameterHintsCommand =
              Some("editor.action.triggerParameterHints"),
            _completionCommand = Some("editor.action.triggerSuggest"),
            overrideDefFormat = OverrideDefFormat.Unicode,
          ),
        )
      case MetalsClientType.helix =>
        base.copy(
          disableShowMessageRequest = true
        )
      case MetalsClientType.vimLsc =>
        base.copy(
          // window/logMessage output is always visible and non-invasive in vim-lsc
          statusBar = StatusBarConfig.logMessage,
          isHttpEnabled = true,
          icons = Icons.unicode,
          compilers = base.compilers.copy(
            snippetAutoIndent = false
          ),
        )
      case MetalsClientType.cocNvim =>
        base.copy(
          statusBar = StatusBarConfig.showMessage,
          isHttpEnabled = true,
          compilers = base.compilers.copy(
            _parameterHintsCommand =
              Some("editor.action.triggerParameterHints"),
            _completionCommand = Some("editor.action.triggerSuggest"),
            overrideDefFormat = OverrideDefFormat.Unicode,
            isCompletionItemResolve = false,
          ),
        )
      case MetalsClientType.cocMetals =>
        base.copy(
          compilers = base.compilers.copy(
            _parameterHintsCommand =
              Some("editor.action.triggerParameterHints"),
            _completionCommand = Some("editor.action.triggerSuggest"),
            overrideDefFormat = OverrideDefFormat.Unicode,
            isCompletionItemResolve = false,
          )
        )
      case MetalsClientType.sublime =>
        base.copy(
          isHttpEnabled = true,
          statusBar = StatusBarConfig.showMessage,
          icons = Icons.unicode,
          isExitOnShutdown = true,
          compilers = base.compilers.copy(
            // Avoid showing the method signature twice because it's already visible in the label.
            isCompletionItemDetailEnabled = false
          ),
        )
      case MetalsClientType.emacs =>
        base.copy(
          executeClientCommand = ExecuteClientCommandConfig.on,
          compilers = base.compilers.copy(
            snippetAutoIndent = false,
            isDetailIncludedInLabel = false,
          ),
        )
      case _ =>
        base
    }
  }
}
