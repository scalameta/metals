package scala.meta.internal.metals

import scala.meta.internal.metals.Configs.GlobSyntaxConfig
import scala.meta.internal.metals.config.DoctorFormat
import scala.meta.internal.metals.config.StatusBarState

/**
 * This class provides a uniform way to know how the client is configured
 * using a combination of server properties, `clientExperimentalCapabilities`
 * and `initializationOptions`.
 *
 * @param initalConfig Initial server properties
 * @param experimentalCapabilities clientExperimentalCapabilities
 * @param initializationOptions initializationOptions
 */
class ClientConfiguration(
    var initialConfig: MetalsServerConfig,
    var experimentalCapabilities: ClientExperimentalCapabilities,
    var initializationOptions: InitializationOptions
) {

  def extract[T](primary: Option[T], secondary: Option[T], default: T): T = {
    primary.orElse(secondary).getOrElse(default)
  }

  def statusBarState(): StatusBarState.StatusBarState =
    extract(
      initializationOptions.statusBarState,
      experimentalCapabilities.statusBarState,
      StatusBarState
        .fromString(initialConfig.statusBar.value)
        .getOrElse(StatusBarState.Off)
    )

  def globSyntax(): GlobSyntaxConfig =
    initializationOptions.globSyntax
      .flatMap(GlobSyntaxConfig.fromString)
      .getOrElse(initialConfig.globSyntax)

  def renameFileThreshold(): Int =
    initializationOptions.renameFileThreshold.getOrElse(
      initialConfig.renameFileThreshold
    )

  def isCommandInHtmlSupported(): Boolean =
    extract(
      initializationOptions.isCommandInHtmlSupported,
      experimentalCapabilities.isCommandInHtmlSupported,
      initialConfig.isCommandInHtmlSupported
    )

  def icons(): Icons =
    initializationOptions.icons
      .map(Icons.fromString)
      .getOrElse(initialConfig.icons)

  def slowTaskIsOn(): Boolean =
    extract(
      initializationOptions.slowTaskProvider,
      experimentalCapabilities.slowTaskProvider,
      initialConfig.slowTask.isOn
    )

  def isExecuteClientCommandProvider(): Boolean =
    extract(
      initializationOptions.executeClientCommandProvider,
      experimentalCapabilities.executeClientCommandProvider,
      initialConfig.executeClientCommand.isOn
    )

  def isInputBoxEnabled(): Boolean =
    extract(
      initializationOptions.inputBoxProvider,
      experimentalCapabilities.inputBoxProvider,
      initialConfig.isInputBoxEnabled
    )

  def isQuickPickProvider(): Boolean =
    extract(
      initializationOptions.quickPickProvider,
      experimentalCapabilities.quickPickProvider,
      false
    )

  def isOpenFilesOnRenameProvider(): Boolean =
    extract(
      initializationOptions.openFilesOnRenameProvider,
      experimentalCapabilities.openFilesOnRenameProvider,
      initialConfig.openFilesOnRenames
    )

  def doctorFormat(): DoctorFormat.DoctorFormat =
    extract(
      initializationOptions.doctorFormat,
      experimentalCapabilities.doctorFormat,
      Option(System.getProperty("metals.doctor-format"))
        .flatMap(DoctorFormat.fromString)
        .getOrElse(DoctorFormat.Html)
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
      false
    )

  def isDecorationProvider(): Boolean =
    extract(
      initializationOptions.decorationProvider,
      experimentalCapabilities.decorationProvider,
      false
    )

  def isTreeViewProvider(): Boolean =
    extract(
      initializationOptions.treeViewProvider,
      experimentalCapabilities.treeViewProvider,
      false
    )

  def isDidFocusProvider(): Boolean =
    extract(
      initializationOptions.didFocusProvider,
      experimentalCapabilities.didFocusProvider,
      false
    )

  def isOpenNewWindowProvider(): Boolean =
    initializationOptions.openNewWindowProvider.getOrElse(false)
}

object ClientConfiguration {
  def Default() =
    new ClientConfiguration(
      MetalsServerConfig(),
      ClientExperimentalCapabilities.Default,
      InitializationOptions.Default
    )
}
