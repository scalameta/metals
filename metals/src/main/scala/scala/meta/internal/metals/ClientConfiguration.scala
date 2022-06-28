package scala.meta.internal.metals

import scala.meta.internal.metals.Configs.GlobSyntaxConfig
import scala.meta.internal.metals.config.DoctorFormat
import scala.meta.internal.metals.config.StatusBarState

import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.InitializeParams

/**
 * This class provides a uniform way to know how the client is configured
 * using a combination of server properties, `clientExperimentalCapabilities`
 * and `initializationOptions`.
 *
 * @param initalConfig Initial server properties
 */
case class ClientConfiguration(initialConfig: MetalsServerConfig) {

  private var experimentalCapabilities = ClientExperimentalCapabilities.Default
  private var initializationOptions = InitializationOptions.Default
  private var clientCapabilities: Option[ClientCapabilities] = None

  def update(params: InitializeParams): Unit = {
    experimentalCapabilities = ClientExperimentalCapabilities.from(
      params.getCapabilities
    )
    initializationOptions = InitializationOptions.from(params)
    clientCapabilities = Some(params.getCapabilities())
  }

  def extract[T](primary: Option[T], secondary: Option[T], default: T): T = {
    primary.orElse(secondary).getOrElse(default)
  }

  def statusBarState(): StatusBarState.StatusBarState =
    extract(
      initializationOptions.statusBarState,
      experimentalCapabilities.statusBarState,
      StatusBarState
        .fromString(initialConfig.statusBar.value)
        .getOrElse(StatusBarState.Off),
    )

  def globSyntax(): GlobSyntaxConfig =
    initializationOptions.globSyntax
      .flatMap(GlobSyntaxConfig.fromString)
      .getOrElse(initialConfig.globSyntax)

  def renameFileThreshold(): Int =
    initializationOptions.renameFileThreshold.getOrElse(
      initialConfig.renameFileThreshold
    )

  def commandInHtmlFormat(): Option[CommandHTMLFormat] =
    initializationOptions.commandInHtmlFormat

  def isVirtualDocumentSupported(): Boolean =
    initializationOptions.isVirtualDocumentSupported.getOrElse(false)

  def isLibraryFileSystemSupported(): Boolean =
    initializationOptions.isLibraryFileSystemSupported.getOrElse(false)

  def icons(): Icons =
    initializationOptions.icons
      .map(Icons.fromString)
      .getOrElse(initialConfig.icons)

  def slowTaskIsOn(): Boolean =
    extract(
      initializationOptions.slowTaskProvider,
      experimentalCapabilities.slowTaskProvider,
      initialConfig.slowTask.isOn,
    )

  def isExecuteClientCommandProvider(): Boolean =
    extract(
      initializationOptions.executeClientCommandProvider,
      experimentalCapabilities.executeClientCommandProvider,
      initialConfig.executeClientCommand.isOn,
    )

  def isInputBoxEnabled(): Boolean =
    extract(
      initializationOptions.inputBoxProvider,
      experimentalCapabilities.inputBoxProvider,
      initialConfig.isInputBoxEnabled,
    )

  def isQuickPickProvider(): Boolean =
    extract(
      initializationOptions.quickPickProvider,
      experimentalCapabilities.quickPickProvider,
      false,
    )

  def isOpenFilesOnRenameProvider(): Boolean =
    extract(
      initializationOptions.openFilesOnRenameProvider,
      experimentalCapabilities.openFilesOnRenameProvider,
      initialConfig.openFilesOnRenames,
    )

  def doctorFormat(): DoctorFormat.DoctorFormat =
    extract(
      initializationOptions.doctorFormat,
      experimentalCapabilities.doctorFormat,
      Option(System.getProperty("metals.doctor-format"))
        .flatMap(DoctorFormat.fromString)
        .getOrElse(DoctorFormat.Html),
    )

  def isHttpEnabled(): Boolean =
    initializationOptions.isHttpEnabled.getOrElse(initialConfig.isHttpEnabled)

  def isExitOnShutdown(): Boolean =
    initializationOptions.isExitOnShutdown.getOrElse(
      initialConfig.isExitOnShutdown
    )

  def isCompletionItemResolve(): Boolean =
    initializationOptions.compilerOptions.isCompletionItemResolve.getOrElse(
      initialConfig.compilers.isCompletionItemResolve
    )

  def isDebuggingProvider(): Boolean =
    extract(
      initializationOptions.debuggingProvider,
      experimentalCapabilities.debuggingProvider,
      false,
    )

  def isDecorationProvider(): Boolean =
    extract(
      initializationOptions.decorationProvider,
      experimentalCapabilities.decorationProvider,
      false,
    )

  def isInlineDecorationProvider(): Boolean =
    initializationOptions.inlineDecorationProvider.getOrElse(false)

  def isTreeViewProvider(): Boolean =
    extract(
      initializationOptions.treeViewProvider,
      experimentalCapabilities.treeViewProvider,
      false,
    )

  def isTestExplorerProvider(): Boolean =
    initializationOptions.testExplorerProvider.getOrElse(false)

  def isDidFocusProvider(): Boolean =
    extract(
      initializationOptions.didFocusProvider,
      experimentalCapabilities.didFocusProvider,
      false,
    )

  def isOpenNewWindowProvider(): Boolean =
    initializationOptions.openNewWindowProvider.getOrElse(false)

  def isCopyWorksheetOutputProvider(): Boolean =
    initializationOptions.copyWorksheetOutputProvider.getOrElse(false)

  def disableColorOutput(): Boolean =
    initializationOptions.disableColorOutput.getOrElse(false)

  def isDoctorVisibilityProvider(): Boolean =
    initializationOptions.doctorVisibilityProvider.getOrElse(false)

  def codeLenseRefreshSupport(): Boolean = {
    val codeLenseRefreshSupport: Option[Boolean] = for {
      capabilities <- clientCapabilities
      workspace <- Option(capabilities.getWorkspace())
      codeLens <- Option(workspace.getCodeLens())
      refreshSupport <- Option(codeLens.getRefreshSupport())
    } yield refreshSupport
    codeLenseRefreshSupport.getOrElse(false)
  }
}

object ClientConfiguration {
  def Default(): ClientConfiguration = ClientConfiguration(MetalsServerConfig())
}
