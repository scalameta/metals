package scala.meta.internal.metals

import scala.meta.internal.metals.Configs.GlobSyntaxConfig
import scala.meta.internal.metals.config.DoctorFormat
import scala.meta.internal.metals.config.StatusBarState
import scala.meta.pc.ContentType

import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.InitializeParams

/**
 * This class provides a uniform way to know how the client is configured
 * using a combination of server properties, `clientExperimentalCapabilities`
 * and `initializationOptions`.
 *
 * @param initalConfig Initial server properties
 * @param initializeParams The initialize params sent from the client in the first request
 */
final class ClientConfiguration(
    val initialConfig: MetalsServerConfig,
    initializeParams: Option[InitializeParams],
) {

  private val clientCapabilities: Option[ClientCapabilities] =
    initializeParams.flatMap(params => Option(params.getCapabilities))

  private val experimentalCapabilities =
    clientCapabilities
      .fold(ClientExperimentalCapabilities.Default)(
        ClientExperimentalCapabilities.from
      )

  private val initializationOptions =
    initializeParams.fold(InitializationOptions.Default)(
      InitializationOptions.from
    )

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

  def bspStatusBarState(): StatusBarState.StatusBarState =
    extract(
      initializationOptions.bspStatusBarState,
      StatusBarState.fromString(initialConfig.bspStatusBar.value),
      StatusBarState.LogMessage,
    )

  def moduleStatusBarState(): StatusBarState.StatusBarState =
    extract(
      initializationOptions.moduleStatusBarState,
      StatusBarState.fromString(initialConfig.moduleStatusBar.value),
      StatusBarState.Off,
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

  def icons(): Icons =
    initializationOptions.icons
      .map(Icons.fromString)
      .getOrElse(initialConfig.icons)

  def hasWorkDoneProgressCapability(): Boolean =
    (for {
      params <- initializeParams
      capabilities <- Option(params.getCapabilities())
      window <- Option(capabilities.getWindow())
      workDoneProgress <- Option(window.getWorkDoneProgress())
    } yield workDoneProgress.booleanValue()).getOrElse(false)

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

  def isRunProvider(): Boolean =
    initializationOptions.runProvider.getOrElse(false)

  def isInlayHintsEnabled(): Boolean = {
    for {
      capabilities <- clientCapabilities
      textDocumentCapabilities <- Option(capabilities.getTextDocument())
      _ <- Option(textDocumentCapabilities.getInlayHint())
    } yield true
  }.getOrElse(false)

  def isInlayHintsRefreshEnabled(): Boolean = {
    for {
      capabilities <- clientCapabilities
      workspace <- Option(capabilities.getWorkspace())
      inlayHints <- Option(workspace.getInlayHint())
      refreshSupport <- Option(inlayHints.getRefreshSupport())
    } yield refreshSupport.booleanValue()
  }.getOrElse(false)

  def hoverContentType(): ContentType =
    (for {
      capabilities <- clientCapabilities
      textDocumentCapabilities <- Option(capabilities.getTextDocument())
      hoverCapabilities <- Option(textDocumentCapabilities.getHover())
      contentTypes <- Option(hoverCapabilities.getContentFormat())
    } yield {
      if (contentTypes.contains(ContentType.MARKDOWN.toString()))
        ContentType.MARKDOWN
      else ContentType.PLAINTEXT
    }).getOrElse(ContentType.MARKDOWN)

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

  def debuggeeStartTimeout(): Int =
    initializationOptions.debuggeeStartTimeout.getOrElse(
      initialConfig.debuggeeStartTimeout
    )

  def codeLenseRefreshSupport(): Boolean = {
    val codeLenseRefreshSupport: Option[Boolean] = for {
      capabilities <- clientCapabilities
      workspace <- Option(capabilities.getWorkspace())
      codeLens <- Option(workspace.getCodeLens())
      refreshSupport <- Option(codeLens.getRefreshSupport())
    } yield refreshSupport
    codeLenseRefreshSupport.getOrElse(false)
  }

  def semanticTokensRefreshSupport(): Boolean = {
    val semanticTokensRefreshSupport: Option[Boolean] = for {
      capabilities <- clientCapabilities
      workspace <- Option(capabilities.getWorkspace())
      semanticTokens <- Option(workspace.getSemanticTokens())
      refreshSupport <- Option(semanticTokens.getRefreshSupport())
    } yield refreshSupport
    semanticTokensRefreshSupport.getOrElse(false)
  }
}

object ClientConfiguration {
  val default: ClientConfiguration = ClientConfiguration(MetalsServerConfig())

  def apply(
      initialConfig: MetalsServerConfig,
      initializeParams: InitializeParams,
  ): ClientConfiguration = {
    new ClientConfiguration(initialConfig, Some(initializeParams))
  }

  def apply(
      initialConfig: MetalsServerConfig
  ): ClientConfiguration = {
    new ClientConfiguration(initialConfig, None)
  }
}
