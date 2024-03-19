package scala.meta.internal.telemetry

import scala.collection.JavaConverters._
import scala.jdk.OptionConverters._

import scala.meta.internal.bsp
import scala.meta.internal.metals
import scala.meta.internal.telemetry
import scala.meta.pc

import org.eclipse.lsp4j

package object conversion {
  def UserConfiguration(
      config: metals.UserConfiguration
  ): MetalsUserConfiguration =
    MetalsUserConfiguration(
      symbolPrefixes = config.symbolPrefixes,
      bloopSbtAlreadyInstalled = config.bloopSbtAlreadyInstalled,
      bloopVersion = config.bloopVersion,
      bloopJvmProperties = config.bloopJvmProperties.toList.flatten,
      ammoniteJvmProperties = config.ammoniteJvmProperties.toList.flatten,
      superMethodLensesEnabled = config.superMethodLensesEnabled,
      showInferredType = config.showInferredType,
      showImplicitArguments = config.showImplicitArguments,
      showImplicitConversionsAndClasses =
        config.showImplicitConversionsAndClasses,
      enableStripMarginOnTypeFormatting =
        config.enableStripMarginOnTypeFormatting,
      enableIndentOnPaste = config.enableIndentOnPaste,
      enableSemanticHighlighting = config.enableSemanticHighlighting,
      excludedPackages = config.excludedPackages.toList.flatten,
      fallbackScalaVersion = config.fallbackScalaVersion,
      testUserInterface = TestUserInterfaceKind(config.testUserInterface),
    )

  def PresentationCompilerConfig(
      config: pc.PresentationCompilerConfig
  ): telemetry.PresentationCompilerConfig =
    telemetry.PresentationCompilerConfig(
      symbolPrefixes = config.symbolPrefixes().asScala.toMap,
      completionCommand = config.completionCommand().asScala,
      parameterHintsCommand = config.parameterHintsCommand().asScala,
      overrideDefFormat = config.overrideDefFormat.name(),
      isDefaultSymbolPrefixes = config.isDefaultSymbolPrefixes(),
      isCompletionItemDetailEnabled = config.isCompletionItemDetailEnabled(),
      isStripMarginOnTypeFormattingEnabled =
        config.isStripMarginOnTypeFormattingEnabled(),
      isCompletionItemDocumentationEnabled =
        config.isCompletionItemDocumentationEnabled(),
      isHoverDocumentationEnabled = config.isHoverDocumentationEnabled(),
      snippetAutoIndent = config.snippetAutoIndent(),
      isSignatureHelpDocumentationEnabled =
        config.isSignatureHelpDocumentationEnabled(),
      isCompletionSnippetsEnabled = config.isCompletionSnippetsEnabled(),
      semanticdbCompilerOptions =
        config.semanticdbCompilerOptions.asScala.toList,
    )

  def BuildServerConnections(
      session: bsp.BspSession
  ): List[BuildServerConnection] = {
    def convert(conn: metals.BuildServerConnection, isMain: Boolean) =
      BuildServerConnection(conn.name, conn.version, isMain)

    convert(session.main, isMain = true) ::
      session.meta.map(convert(_, isMain = false))
  }

  def TestUserInterfaceKind(kind: metals.TestUserInterfaceKind): String =
    kind match {
      case metals.TestUserInterfaceKind.CodeLenses => "CodeLenses"
      case metals.TestUserInterfaceKind.TestExplorer => "TestExplorer"
    }

  def MetalsServerConfig(
      config: metals.MetalsServerConfig
  ): MetalsServerConfiguration =
    MetalsServerConfiguration(
      config.executeClientCommand.value,
      config.snippetAutoIndent,
      config.isHttpEnabled,
      config.isInputBoxEnabled,
      config.askToReconnect,
      config.allowMultilineStringFormatting,
      PresentationCompilerConfig(config.compilers),
    )

  def MetalsClientInfo(info: lsp4j.ClientInfo): telemetry.MetalsClientInfo =
    telemetry.MetalsClientInfo(
      Option(info.getName()),
      Option(info.getVersion()),
    )

}
