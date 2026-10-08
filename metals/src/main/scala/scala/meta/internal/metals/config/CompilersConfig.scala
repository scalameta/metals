package scala.meta.internal.metals.config

import java.util.Properties

import scala.meta.internal.infra.NoopFeatureFlagProvider
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.pc.PresentationCompilerConfigImpl
import scala.meta.pc.PresentationCompilerConfig.OverrideDefFormat
import scala.meta.pc.SourcePathMode

object CompilersConfig {
  def apply(
      props: Properties = System.getProperties
  ): PresentationCompilerConfigImpl = {
    PresentationCompilerConfigImpl(
      debug =
        MetalsServerConfig.binaryOption("metals.pc.debug", default = false),
      _parameterHintsCommand =
        Option(props.getProperty("metals.signature-help.command")),
      _completionCommand =
        Option(props.getProperty("metals.completion.command")),
      overrideDefFormat =
        props.getProperty("metals.override-def-format") match {
          case "unicode" => OverrideDefFormat.Unicode
          case "ascii" => OverrideDefFormat.Ascii
          case _ => OverrideDefFormat.Ascii
        },
      isCompletionItemDetailEnabled = MetalsServerConfig.binaryOption(
        "metals.completion-item.detail",
        default = true,
      ),
      isCompletionItemDocumentationEnabled = MetalsServerConfig.binaryOption(
        "metals.completion-item.documentation",
        default = true,
      ),
      isHoverDocumentationEnabled = MetalsServerConfig.binaryOption(
        "metals.hover.documentation",
        default = true,
      ),
      snippetAutoIndent = MetalsServerConfig.binaryOption(
        "metals.snippet-auto-indent",
        default = true,
      ),
      isSignatureHelpDocumentationEnabled = MetalsServerConfig.binaryOption(
        "metals.signature-help.documentation",
        default = true,
      ),
      isCompletionItemResolve = MetalsServerConfig.binaryOption(
        "metals.completion-item.resolve",
        default = true,
      ),
      sourcePathMode = SourcePathConfig
        .fromConfigOrFeatureFlag(
          Option(props.getProperty("metals.source-path")),
          NoopFeatureFlagProvider,
          default = SourcePathMode.PRUNED,
        )
        .toOption
        .getOrElse(SourcePathMode.PRUNED),
    )
  }
}
