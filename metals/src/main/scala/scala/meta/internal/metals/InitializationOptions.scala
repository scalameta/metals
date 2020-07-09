package scala.meta.internal.metals

import scala.meta.internal.pc.CompilerInitializationOptions

import com.google.gson.JsonObject
import org.eclipse.{lsp4j => l}

/**
 * Whever possible, this is the preferred way to configure Metals from the client.
 * Eventually this will be accumulated in the ClientConfiguration along with
 * ClientExperimentalCapabilities and the InitialConfig. If the values aren't directly
 * passed in here, we default everything to None to signify that the client didn't
 * directly set the value. The defaults will then be handled by the ClientConfiguration
 * so we don't need to worry about them here.
 */
final case class InitializationOptions(
    compilerOptions: CompilerInitializationOptions,
    debuggingProvider: Option[Boolean],
    decorationProvider: Option[Boolean],
    didFocusProvider: Option[Boolean],
    doctorProvider: Option[String],
    executeClientCommandProvider: Option[Boolean],
    inputBoxProvider: Option[Boolean],
    isExitOnShutdown: Option[Boolean],
    isHttpEnabled: Option[Boolean],
    openFilesOnRenameProvider: Option[Boolean],
    quickPickProvider: Option[Boolean],
    slowTaskProvider: Option[Boolean],
    statusBarProvider: Option[String],
    treeViewProvider: Option[Boolean],
    openNewWindowProvider: Option[Boolean]
) {
  def doctorFormatIsJson: Boolean = doctorProvider.exists(_ == "json")
  def statusBarIsOn: Boolean = statusBarProvider.exists(_ == "on")
  def statusBarIsOff: Boolean = statusBarProvider.exists(_ == "off")
  def statusBarIsShowMessage: Boolean =
    statusBarProvider.exists(_ == "show-message")
  def statusBarIsLogMessage: Boolean =
    statusBarProvider.exists(_ == "log-message")
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
    val compilerOptions = extractCompilerOptions(jsonObj)
    val debuggingProvider = jsonObj.getBooleanOption("debuggingProvider")
    val decorationProvider =
      jsonObj.getBooleanOption("decorationProvider")
    val didFocusProvider = jsonObj.getBooleanOption("didFocusProvider")
    val doctorProvider = jsonObj.getStringOption("doctorProvider")
    val executeClientCommandProvider =
      jsonObj.getBooleanOption("executeClientCommandProvider")
    val inputBoxProvider = jsonObj.getBooleanOption("inputBoxProvider")
    val isExitOnShutdown = jsonObj.getBooleanOption("isExitOnShutdown")
    val isHttpEnabled = jsonObj.getBooleanOption("isHttpEnabled")
    val openFilesOnRenameProvider =
      jsonObj.getBooleanOption("openFilesOnRenameProvider")
    val quickPickProvider = jsonObj.getBooleanOption("quickPickProvider")
    val slowTaskProvider = jsonObj.getBooleanOption("slowTaskProvider")
    val statusBarProvider = jsonObj.getStringOption("statusBarProvider")
    val treeViewProvider = jsonObj.getBooleanOption("treeViewProvider")
    val openNewWindowProvider =
      jsonObj.getBooleanOption("openNewWindowProvider")
    InitializationOptions(
      compilerOptions,
      debuggingProvider,
      decorationProvider,
      didFocusProvider,
      doctorProvider,
      executeClientCommandProvider,
      inputBoxProvider,
      isExitOnShutdown,
      isHttpEnabled,
      openFilesOnRenameProvider,
      quickPickProvider,
      slowTaskProvider,
      statusBarProvider,
      treeViewProvider,
      openNewWindowProvider
    )
  }

  def extractCompilerOptions(
      json: JsonObject
  ): CompilerInitializationOptions = {
    val compilerObj = json.getObjectOption("compilerOptions")
    CompilerInitializationOptions(
      isCompletionItemDetailEnabled = compilerObj.flatMap(
        _.getBooleanOption("isCompletionItemDetailEnabled")
      ),
      isCompletionItemDocumentationEnabled = compilerObj.flatMap(
        _.getBooleanOption("isCompletionItemDocumentationEnabled")
      ),
      isHoverDocumentationEnabled = compilerObj.flatMap(
        _.getBooleanOption("isHoverDocumentationEnabled")
      ),
      snippetAutoIndent =
        compilerObj.flatMap(_.getBooleanOption("snippetAutoIndent")),
      isSignatureHelpDocumentationEnabled = compilerObj.flatMap(
        _.getBooleanOption("isSignatureHelpDocumentationEnabled")
      ),
      isCompletionItemResolve =
        compilerObj.flatMap(_.getBooleanOption("isCompletionItemResolve"))
    )
  }

}
