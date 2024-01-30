package scala.meta.internal.telemetry

import java.{util => ju}

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

  def PresentationCompilerConfig(
      config: pc.PresentationCompilerConfig
  ): telemetry.PresentationCompilerConfig =
    new telemetry.PresentationCompilerConfig(
      copyOf(config.symbolPrefixes),
      config.completionCommand,
      config.parameterHintsCommand(),
      config.overrideDefFormat.name(),
      config.isDefaultSymbolPrefixes(),
      config.isCompletionItemDetailEnabled(),
      config.isStripMarginOnTypeFormattingEnabled(),
      config.isCompletionItemDocumentationEnabled(),
      config.isHoverDocumentationEnabled(),
      config.snippetAutoIndent(),
      config.isSignatureHelpDocumentationEnabled(),
      config.isCompletionSnippetsEnabled(),
      copyOf(config.semanticdbCompilerOptions),
    )

  // Java Collections utilities not available in JDK 8
  private def copyOf[T](v: ju.List[T]): ju.List[T] = {
    val copy = new ju.ArrayList[T](v.size())
    copy.addAll(v)
    copy
  }

  private def copyOf[K, V](v: ju.Map[K, V]): ju.Map[K, V] = {
    val copy = new ju.HashMap[K, V](v.size())
    copy.putAll(v)
    copy
  }

  def BuildServerConnections(
      session: bsp.BspSession
  ): List[BuildServerConnection] = {
    def convert(
        conn: metals.BuildServerConnection,
        isMain: Boolean,
    ) = new BuildServerConnection(
      /* name = */ conn.name,
      /* version = */ conn.version,
      /* isMain = */ isMain,
    )
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
    new MetalsServerConfiguration(
      /* executeClientCommand = */ config.executeClientCommand.value,
      /* snippetAutoIndent = */ config.snippetAutoIndent,
      /* isHttpEnabled = */ config.isHttpEnabled,
      /* isInputBoxEnabled = */ config.isInputBoxEnabled,
      /* askToReconnect = */ config.askToReconnect,
      /* allowMultilineStringFormatting = */ config.allowMultilineStringFormatting,
      /* compilers = */ PresentationCompilerConfig(config.compilers),
    )

  def MetalsClientInfo(info: lsp4j.ClientInfo): telemetry.MetalsClientInfo =
    new telemetry.MetalsClientInfo(
      /* name = */ ju.Optional.of(info.getName()),
      /* version =  */ ju.Optional.of(info.getVersion()),
    )

}
