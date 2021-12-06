package scala.meta.internal.metals

import scala.meta.internal.metals.config.DoctorFormat
import scala.meta.internal.metals.config.StatusBarState
import scala.meta.internal.pc.CompilerInitializationOptions

import com.google.gson.JsonObject
import org.eclipse.{lsp4j => l}

/**
 * This is the preferred way to configure Metals from the client.
 * Eventually this will be accumulated in the ClientConfiguration along with
 * ClientExperimentalCapabilities and the InitialConfig. If the values aren't directly
 * passed in here, we default everything to None to signify that the client didn't
 * directly set the value. The defaults will then be handled by the ClientConfiguration
 * so we don't need to worry about them here.
 *
 * @param compilerOptions configuration for the `PresentationCompilerConfig`.
 * @param debuggingProvider if the client supports debugging.
 * @param decorationProvider if the client implements the Metals Decoration Protocol.
 * @param inlineDecorationProvider if the client implements the Metals Decoration Protocol
 *                                 and supports decorations to be shown inline and not
 *                                 only at the end of a line.
 * @param didFocusProvider if the client implements the `metals/didFocusTextDocument` command.
 * @param doctorProvider format that the client would like the Doctor to be returned in.
 * @param executeClientCommandProvider if the client implements `metals/executeClientCommand`.
 * @param globSyntax pattern used for `DidChangeWatchedFilesRegistrationOptions`.
 * @param icons which icons will be used for messages.
 * @param inputBoxProvider if the client implements `metals/inputBox`.
 * @param isExitOnShutdown whether the client needs Metals to shut down manually on exit.
 * @param isHttpEnabled whether the client needs Metals to start an HTTP client interface.
 * @param openFilesOnRenameProvider whether or not the client supports opening files on rename.
 * @param quickPickProvider if the client implements `metals/quickPick`.
 * @param renameFileThreshold amount of files that should be opened during rename if client
 *                            is a `openFilesOnRenameProvider`.
 * @param slowTaskProvider if the client implements `metals/slowTask`.
 * @param statusBarProvider if the client implements `metals/status`.
 * @param treeViewProvider if the client implements the Metals Tree View Protocol.
 * @param textExplorerProvider if the client implements the Text Explorer UI.
 * @param openNewWindowProvider if the client can open a new window after new project creation.
 * @param copyWorksheetOutputProvider if the client can execute server CopyWorksheet command and
 *                                    copy results to the local buffer.
 * @param disableColorOutput in the situation where your DAP client may not handle color codes in
 *                            the output, you can enable this to strip them.
 */
final case class InitializationOptions(
    compilerOptions: CompilerInitializationOptions,
    debuggingProvider: Option[Boolean],
    decorationProvider: Option[Boolean],
    inlineDecorationProvider: Option[Boolean],
    didFocusProvider: Option[Boolean],
    doctorProvider: Option[String],
    executeClientCommandProvider: Option[Boolean],
    globSyntax: Option[String],
    icons: Option[String],
    inputBoxProvider: Option[Boolean],
    isExitOnShutdown: Option[Boolean],
    isHttpEnabled: Option[Boolean],
    isCommandInHtmlSupported: Option[Boolean],
    openFilesOnRenameProvider: Option[Boolean],
    quickPickProvider: Option[Boolean],
    renameFileThreshold: Option[Int],
    slowTaskProvider: Option[Boolean],
    statusBarProvider: Option[String],
    treeViewProvider: Option[Boolean],
    testExplorerProvider: Option[Boolean],
    openNewWindowProvider: Option[Boolean],
    copyWorksheetOutputProvider: Option[Boolean],
    disableColorOutput: Option[Boolean]
) {
  def doctorFormat: Option[DoctorFormat.DoctorFormat] =
    doctorProvider.flatMap(DoctorFormat.fromString)

  def statusBarState: Option[StatusBarState.StatusBarState] =
    statusBarProvider.flatMap(StatusBarState.fromString)
}

object InitializationOptions {
  import scala.meta.internal.metals.JsonParser._

  val Default: InitializationOptions = InitializationOptions(
    CompilerInitializationOptions.default,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None
  )

  def from(
      initializeParams: l.InitializeParams
  ): InitializationOptions = {
    initializeParams.getInitializationOptions() match {
      case json: JsonObject =>
        // Note (ckipp01) We used to do this with reflection going from the JsonElement straight to
        // InitializationOptions, but there was a lot of issues with it such as setting
        // nulls for unset values, needing a default constructor, and not being able to
        // work as expected with Options. This is a bit more verbose, but it gives us full
        // control over how the InitializationOptions are created.
        extractToInitializationOptions(json)
      case _ =>
        Default
    }
  }

  def extractToInitializationOptions(
      json: JsonObject
  ): InitializationOptions = {
    val jsonObj = json.toJsonObject
    InitializationOptions(
      compilerOptions = extractCompilerOptions(jsonObj),
      debuggingProvider = jsonObj.getBooleanOption("debuggingProvider"),
      decorationProvider = jsonObj.getBooleanOption("decorationProvider"),
      inlineDecorationProvider =
        jsonObj.getBooleanOption("inlineDecorationProvider"),
      didFocusProvider = jsonObj.getBooleanOption("didFocusProvider"),
      doctorProvider = jsonObj.getStringOption("doctorProvider"),
      executeClientCommandProvider =
        jsonObj.getBooleanOption("executeClientCommandProvider"),
      globSyntax = jsonObj.getStringOption("globSyntax"),
      icons = jsonObj.getStringOption("icons"),
      inputBoxProvider = jsonObj.getBooleanOption("inputBoxProvider"),
      isExitOnShutdown = jsonObj.getBooleanOption("isExitOnShutdown"),
      isHttpEnabled = jsonObj.getBooleanOption("isHttpEnabled"),
      isCommandInHtmlSupported =
        jsonObj.getBooleanOption("isCommandInHtmlSupported"),
      openFilesOnRenameProvider =
        jsonObj.getBooleanOption("openFilesOnRenameProvider"),
      quickPickProvider = jsonObj.getBooleanOption("quickPickProvider"),
      renameFileThreshold = jsonObj.getIntOption("renameFileThreshold"),
      slowTaskProvider = jsonObj.getBooleanOption("slowTaskProvider"),
      statusBarProvider = jsonObj.getStringOption("statusBarProvider"),
      treeViewProvider = jsonObj.getBooleanOption("treeViewProvider"),
      testExplorerProvider = jsonObj.getBooleanOption("textExplorerProvider"),
      openNewWindowProvider = jsonObj.getBooleanOption("openNewWindowProvider"),
      copyWorksheetOutputProvider =
        jsonObj.getBooleanOption("copyWorksheetOutputProvider"),
      disableColorOutput = jsonObj.getBooleanOption("disableColorOutput")
    )
  }

  def extractCompilerOptions(
      json: JsonObject
  ): CompilerInitializationOptions = {
    val compilerObj = json.getObjectOption("compilerOptions")
    CompilerInitializationOptions(
      completionCommand = compilerObj.flatMap(
        _.getStringOption("completionCommand")
      ),
      isCompletionItemDetailEnabled = compilerObj.flatMap(
        _.getBooleanOption("isCompletionItemDetailEnabled")
      ),
      isCompletionItemDocumentationEnabled = compilerObj.flatMap(
        _.getBooleanOption("isCompletionItemDocumentationEnabled")
      ),
      isCompletionItemResolve =
        compilerObj.flatMap(_.getBooleanOption("isCompletionItemResolve")),
      isHoverDocumentationEnabled = compilerObj.flatMap(
        _.getBooleanOption("isHoverDocumentationEnabled")
      ),
      isSignatureHelpDocumentationEnabled = compilerObj.flatMap(
        _.getBooleanOption("isSignatureHelpDocumentationEnabled")
      ),
      overrideDefFormat = compilerObj.flatMap(
        _.getStringOption("overrideDefFormat")
      ),
      parameterHintsCommand = compilerObj.flatMap(
        _.getStringOption("parameterHintsCommand")
      ),
      snippetAutoIndent =
        compilerObj.flatMap(_.getBooleanOption("snippetAutoIndent"))
    )
  }

}
