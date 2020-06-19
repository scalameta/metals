package scala.meta.internal.metals

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

  def statusBarIsOn(): Boolean =
    initializationOptions.statusBarIsOn ||
      experimentalCapabilities.statusBarIsOn ||
      initialConfig.statusBar.isOn

  def statusBarIsShow(): Boolean =
    initializationOptions.statusBarIsShowMessage ||
      experimentalCapabilities.statusBarIsShowMessage ||
      initialConfig.statusBar.isShowMessage

  def statusBarIsLog(): Boolean =
    initializationOptions.statusBarIsLogMessage ||
      experimentalCapabilities.statusBarIsLogMessage ||
      initialConfig.statusBar.isLogMessage

  def statusBarIsOff(): Boolean =
    initializationOptions.statusBarIsOff &&
      experimentalCapabilities.statusBarIsOff &&
      initialConfig.statusBar.isOff

  def slowTaskIsOn(): Boolean =
    initializationOptions.slowTaskProvider ||
      experimentalCapabilities.slowTaskProvider ||
      initialConfig.slowTask.isOn

  def isExecuteClientCommandProvider(): Boolean =
    initializationOptions.executeClientCommandProvider ||
      experimentalCapabilities.executeClientCommandProvider ||
      initialConfig.executeClientCommand.isOn

  def isInputBoxEnabled(): Boolean =
    initializationOptions.inputBoxProvider ||
      experimentalCapabilities.inputBoxProvider ||
      initialConfig.isInputBoxEnabled

  def isQuickPickProvider(): Boolean =
    initializationOptions.quickPickProvider ||
      experimentalCapabilities.quickPickProvider

  def isOpenFilesOnRenameProvider(): Boolean =
    initializationOptions.openFilesOnRenameProvider ||
      experimentalCapabilities.openFilesOnRenameProvider ||
      initialConfig.openFilesOnRenames

  def doctorFormatIsJson(): Boolean =
    initializationOptions.doctorFormatIsJson ||
      experimentalCapabilities.doctorFormatIsJson ||
      initialConfig.doctorFormat.isJson

  def isHttpEnabled(): Boolean =
    initializationOptions.isHttpEnabled ||
      initialConfig.isHttpEnabled

  def isExitOnShutdown(): Boolean =
    initializationOptions.isExitOnShutdown ||
      initialConfig.isExitOnShutdown

  def isCompletionItemResolve(): Boolean =
    initializationOptions.compilerOptions.isCompletionItemResolve &&
      initialConfig.compilers.isCompletionItemResolve

  def isDebuggingProvider(): Boolean =
    initializationOptions.debuggingProvider ||
      experimentalCapabilities.debuggingProvider

  def isDecorationProvider(): Boolean =
    initializationOptions.decorationProvider ||
      experimentalCapabilities.decorationProvider

  def isTreeViewProvider(): Boolean =
    initializationOptions.treeViewProvider ||
      experimentalCapabilities.treeViewProvider

  def isDidFocusProvider(): Boolean =
    initializationOptions.didFocusProvider ||
      experimentalCapabilities.didFocusProvider

  def isOpenNewWindowProvider(): Boolean =
    initializationOptions.openNewWindowProvider
}

object ClientConfiguration {
  def Default() =
    new ClientConfiguration(
      MetalsServerConfig(),
      ClientExperimentalCapabilities.Default,
      InitializationOptions.Default
    )
}
