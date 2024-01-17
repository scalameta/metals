package scala.meta.internal.telemetry

import scala.collection.JavaConverters._
import scala.jdk.OptionConverters._

import scala.meta.internal.bsp
import scala.meta.internal.metals
import scala.meta.internal.pc.telemetry.conversion.PresentationCompilerConfig
import scala.meta.internal.telemetry

package object conversion {
  def UserConfiguration(
      config: metals.UserConfiguration
  ): MetalsUserConfiguration =
    new MetalsUserConfiguration(
      /* symbolPrefixes = */ config.symbolPrefixes.asJava,
      /* bloopSbtAlreadyInstalled = */ config.bloopSbtAlreadyInstalled,
      /* bloopVersion = */ config.bloopVersion.toJava,
      /* bloopJvmProperties = */ config.bloopJvmProperties.toList.flatten.asJava,
      /* ammoniteJvmProperties = */ config.ammoniteJvmProperties.toList.flatten.asJava,
      /* superMethodLensesEnabled = */ config.superMethodLensesEnabled,
      /* showInferredType = */ config.showInferredType.toJava,
      /* showImplicitArguments = */ config.showImplicitArguments,
      /* showImplicitConversionsAndClasses = */ config.showImplicitConversionsAndClasses,
      /* enableStripMarginOnTypeFormatting = */ config.enableStripMarginOnTypeFormatting,
      /* enableIndentOnPaste = */ config.enableIndentOnPaste,
      /* enableSemanticHighlighting = */ config.enableSemanticHighlighting,
      /* excludedPackages = */ config.excludedPackages.toList.flatten.asJava,
      /* fallbackScalaVersion = */ config.fallbackScalaVersion.toJava,
      /* testUserInterface = */ TestUserInterfaceKind(config.testUserInterface),
    )

  def BuildServerConnections(
      session: bsp.BspSession
  ): List[telemetry.BuildServerConnection] = {
    def convert(
        conn: metals.BuildServerConnection,
        isMain: Boolean,
    ) = new telemetry.BuildServerConnection(
      /* name = */ conn.name,
      /* version = */ conn.version,
      /* isMain = */ isMain,
    )
    convert(session.main, isMain = true) ::
      session.meta.map(convert(_, isMain = false))
  }

  def TestUserInterfaceKind(
      kind: metals.TestUserInterfaceKind
  ): String = kind match {
    case metals.TestUserInterfaceKind.CodeLenses => "CodeLenses"
    case metals.TestUserInterfaceKind.TestExplorer => "TestExplorer"
  }

  def MetalsServerConfig(
      config: metals.MetalsServerConfig
  ): telemetry.MetalsServerConfiguration =
    new telemetry.MetalsServerConfiguration(
      /* executeClientCommand = */ config.executeClientCommand.value,
      /* snippetAutoIndent = */ config.snippetAutoIndent,
      /* isHttpEnabled = */ config.isHttpEnabled,
      /* isInputBoxEnabled = */ config.isInputBoxEnabled,
      /* askToReconnect = */ config.askToReconnect,
      /* allowMultilineStringFormatting = */ config.allowMultilineStringFormatting,
      /* compilers = */ PresentationCompilerConfig(config.compilers),
    )

}
