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
    var initalConfig: MetalsServerConfig,
    var experimentalCapabilities: ClientExperimentalCapabilities,
    var initializationOptions: InitializationOptions
) {
  private def choose[T](a: T, b: T, default: T): T =
    Option(a).orElse(Option(b)).getOrElse(default)

  private def ensureAll(a: Boolean, b: Boolean, c: Boolean): Boolean = {
    val ensuredA: Boolean = Option(a).getOrElse(true)
    val ensuredB: Boolean = Option(b).getOrElse(true)

    ensuredA && ensuredB && c
  }

  def statusBarIsOn(): Boolean =
    choose(
      initializationOptions.statusBarIsOn,
      experimentalCapabilities.statusBarIsOn,
      initalConfig.statusBar.isOn
    )

  def statusBarIsShow(): Boolean =
    choose(
      initializationOptions.statusBarIsShowMessage,
      experimentalCapabilities.statusBarIsShowMessage,
      initalConfig.statusBar.isShowMessage
    )

  def statusBarIsLog(): Boolean =
    choose(
      initializationOptions.statusBarIsLogMessage,
      experimentalCapabilities.statusBarIsLogMessage,
      initalConfig.statusBar.isLogMessage
    )

  def statusBarIsOff(): Boolean =
    ensureAll(
      initializationOptions.statusBarIsOff,
      experimentalCapabilities.statusBarIsOff,
      initalConfig.statusBar.isOff
    )

  def slowTaskIsOn(): Boolean =
    choose(
      initializationOptions.slowTaskProvider,
      experimentalCapabilities.slowTaskProvider,
      initalConfig.slowTask.isOn
    )

  def isExecuteClientCommandProvider(): Boolean =
    choose(
      initializationOptions.executeClientCommandProvider,
      experimentalCapabilities.executeClientCommandProvider,
      initalConfig.executeClientCommand.isOn
    )

  def isInputBoxEnabled(): Boolean =
    choose(
      initializationOptions.inputBoxProvider,
      experimentalCapabilities.inputBoxProvider,
      initalConfig.isInputBoxEnabled
    )

  def isQuickPickProvider(): Boolean =
    choose(
      initializationOptions.quickPickProvider,
      experimentalCapabilities.quickPickProvider,
      false
    )

  def isOpenFilesOnRenameProvider(): Boolean =
    choose(
      experimentalCapabilities.openFilesOnRenameProvider,
      initalConfig.openFilesOnRenames,
      false
    )

  def doctorFormatIsJson(): Boolean =
    choose(
      initializationOptions.doctorFormatIsJson,
      experimentalCapabilities.doctorFormatIsJson,
      initalConfig.doctorFormat.isJson
    )

  def isHttpEnabled(): Boolean =
    choose(
      initializationOptions.isHttpEnabled,
      initalConfig.isHttpEnabled,
      false
    )

  def isExitOnShutdown(): Boolean =
    choose(
      initializationOptions.isExitOnShutdown,
      initalConfig.isExitOnShutdown,
      false
    )

}
