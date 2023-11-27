package scala.meta.internal.telemetry

import scala.meta.internal.metals
import scala.meta.internal.telemetry
import scala.meta.internal.bsp
import scala.meta.internal.pc.telemetry.conversion.PresentationCompilerConfig

package object conversion {
  def UserConfiguration(
      config: metals.UserConfiguration
  ): MetalsUserConfiguration =
    MetalsUserConfiguration(
      symbolPrefixes = config.symbolPrefixes,
      bloopSbtAlreadyInstalled = config.bloopSbtAlreadyInstalled,
      superMethodLensesEnabled = config.superMethodLensesEnabled,
      showImplicitArguments = config.showImplicitArguments,
      showImplicitConversionsAndClasses =
        config.showImplicitConversionsAndClasses,
      enableStripMarginOnTypeFormatting =
        config.enableStripMarginOnTypeFormatting,
      enableIndentOnPaste = config.enableIndentOnPaste,
      enableSemanticHighlighting = config.enableSemanticHighlighting,
      testUserInterface = TestUserInterfaceKind(config.testUserInterface),
      bloopVersion = config.bloopVersion,
      bloopJvmProperties = config.bloopJvmProperties,
      ammoniteJvmProperties = config.ammoniteJvmProperties,
      showInferredType = config.showInferredType,
      remoteLanguageServer = config.remoteLanguageServer,
      excludedPackages = config.excludedPackages,
      fallbackScalaVersion = config.fallbackScalaVersion,
    )

  def BuildServerConnections(
      session: bsp.BspSession
  ): List[telemetry.BuildServerConnection] = {
    def convert(
        conn: metals.BuildServerConnection,
        isMain: Boolean,
    ) = telemetry.BuildServerConnection(
      name = conn.name,
      version = conn.version,
      isMain = isMain,
    )
    convert(session.main, isMain = true) ::
      session.meta.map(convert(_, isMain = false))
  }

  def TestUserInterfaceKind(
      kind: metals.TestUserInterfaceKind
  ): TestUserInterfaceKind = kind match {
    case metals.TestUserInterfaceKind.CodeLenses =>
      telemetry.TestUserInterfaceKind.CODE_LENSES
    case metals.TestUserInterfaceKind.TestExplorer =>
      telemetry.TestUserInterfaceKind.TEST_EXPLOLER
  }

  def MetalsServerConfig(
      config: metals.MetalsServerConfig
  ): MetalsServerConfiguration = MetalsServerConfiguration(
    executeClientCommand = config.executeClientCommand.value,
    snippetAutoIndent = config.snippetAutoIndent,
    isHttpEnabled = config.isHttpEnabled,
    isInputBoxEnabled = config.isInputBoxEnabled,
    askToReconnect = config.askToReconnect,
    allowMultilineStringFormatting = config.allowMultilineStringFormatting,
    compilers = PresentationCompilerConfig(config.compilers),
  )

}
