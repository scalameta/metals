package scala.meta.internal.metals

import java.util.Properties

import scala.annotation.nowarn

import scala.meta.infra.FeatureFlag
import scala.meta.infra.FeatureFlagProvider
import scala.meta.internal.infra.NoopFeatureFlagProvider
import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.mbt.LMDB
import scala.meta.internal.pc.PresentationCompilerConfigImpl
import scala.meta.io.AbsolutePath
import scala.meta.pc.PresentationCompilerConfig.OverrideDefFormat
import scala.meta.pc.SourcePathMode

import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions
import org.eclipse.lsp4j.FileSystemWatcher
import org.eclipse.lsp4j.jsonrpc.messages.{Either => JEither}

object Configs {

  final case class GlobSyntaxConfig(value: String) {
    import GlobSyntaxConfig._
    def isUri: Boolean = this == uri
    def isVscode: Boolean = this == vscode
    def registrationOptions(
        workspace: AbsolutePath
    ): DidChangeWatchedFilesRegistrationOptions = {
      val root: String =
        if (isVscode) workspace.toString()
        else workspace.toURI.toString.stripSuffix("/")
      new DidChangeWatchedFilesRegistrationOptions(
        (List(
          new FileSystemWatcher(JEither.forLeft(s"$root/**/*.{scala,java}")),
          new FileSystemWatcher(JEither.forLeft(s"$root/*.sbt")),
          new FileSystemWatcher(JEither.forLeft(s"$root/pom.xml")),
          new FileSystemWatcher(JEither.forLeft(s"$root/*.sc")),
          new FileSystemWatcher(JEither.forLeft(s"$root/*?.gradle")),
          new FileSystemWatcher(JEither.forLeft(s"$root/*.gradle.kts")),
          new FileSystemWatcher(
            JEither.forLeft(s"$root/project/*.{scala,sbt}")
          ),
          new FileSystemWatcher(
            JEither.forLeft(s"$root/project/project/*.{scala,sbt}")
          ),
          new FileSystemWatcher(
            JEither.forLeft(s"$root/project/build.properties")
          ),
          new FileSystemWatcher(
            JEither.forLeft(s"$root/.metals/.reports/bloop/*/*")
          ),
          new FileSystemWatcher(JEither.forLeft(s"$root/**/.bsp/*.json")),
        ) ++ bazelPaths(root)).asJava
      )
    }

    def bazelPaths(root: String): List[FileSystemWatcher] =
      List(
        new FileSystemWatcher(JEither.forLeft(s"$root/**/BUILD")),
        new FileSystemWatcher(JEither.forLeft(s"$root/**/BUILD.bazel")),
        new FileSystemWatcher(JEither.forLeft(s"$root/WORKSPACE")),
        new FileSystemWatcher(JEither.forLeft(s"$root/WORKSPACE.bazel")),
        new FileSystemWatcher(JEither.forLeft(s"$root/**/*.bzl")),
        new FileSystemWatcher(JEither.forLeft(s"$root/*.bazelproject")),
      )
  }

  object GlobSyntaxConfig {
    def uri = new GlobSyntaxConfig("uri")
    def vscode = new GlobSyntaxConfig("vscode")
    def default =
      new GlobSyntaxConfig(System.getProperty("metals.glob-syntax", uri.value))
    def fromString(value: String): Option[GlobSyntaxConfig] =
      value match {
        case "vscode" => Some(vscode)
        case "uri" => Some(uri)
        case _ => None
      }
  }

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
          )
          .toOption
          .getOrElse(SourcePathMode.PRUNED),
      )
    }
  }

  object WorkspaceSymbolProviderConfig {
    def mbt: WorkspaceSymbolProviderConfig = WorkspaceSymbolProviderConfig(
      "mbt"
    )
    def bsp: WorkspaceSymbolProviderConfig = WorkspaceSymbolProviderConfig(
      "bsp"
    )
    def default: WorkspaceSymbolProviderConfig = bsp
    def fromConfigOrFeatureFlag(
        value: Option[String],
        featureFlags: FeatureFlagProvider,
    ): Either[String, WorkspaceSymbolProviderConfig] = {
      value match {
        case Some("mbt") if !LMDB.isSupportedOrWarn() =>
          Right(WorkspaceSymbolProviderConfig.bsp)
        case Some(ok @ ("bsp" | "mbt")) =>
          Right(WorkspaceSymbolProviderConfig(ok))
        case Some(invalid) =>
          Left(
            s"invalid config value '$invalid' for workspaceSymbolProvider. Valid values are \"bsp\" and \"mbt\""
          )
        case None =>
          // The config is not explicitly set so fallback to the default, which
          // can optionally be overridden by a feature flag
          val isMbtEnabled = featureFlags
            .readBoolean(FeatureFlag.MBT_WORKSPACE_SYMBOL_PROVIDER)
            .orElse(false)
          if (isMbtEnabled && LMDB.isSupportedOrWarn()) {
            Right(WorkspaceSymbolProviderConfig.mbt)
          } else {
            Right(WorkspaceSymbolProviderConfig.default)
          }
      }
    }
  }
  @nowarn
  final case class WorkspaceSymbolProviderConfig private (val value: String) {
    if (!List("bsp", "mbt").contains(value)) {
      throw new IllegalArgumentException(
        s"only bsp or mbt are accepted, got $value"
      )
    }

    def isMBT: Boolean =
      value == "mbt" // New BSP-free workspace/symbol implementation
    def isBSP: Boolean =
      value == "bsp" // The classic BSP-based workspace/symbol implementation
  }

  @nowarn
  final case class DefinitionIndexStrategy(val value: String) {
    require(List("classpath", "sources").contains(value), value)
    def isClasspath: Boolean =
      value == "classpath"
    def isSources: Boolean =
      value == "sources"
  }

  object DefinitionIndexStrategy {
    def classpath: DefinitionIndexStrategy =
      DefinitionIndexStrategy("classpath")
    def sources: DefinitionIndexStrategy =
      DefinitionIndexStrategy("sources")
    def default: DefinitionIndexStrategy = sources
    def fromConfigOrFeatureFlag(
        value: Option[String],
        featureFlags: FeatureFlagProvider,
    ): Either[String, DefinitionIndexStrategy] = {
      value match {
        case Some(ok @ ("classpath" | "sources")) =>
          Right(DefinitionIndexStrategy(ok))
        case Some(invalid) =>
          Left(
            s"invalid config value '$invalid' for definitionIndexStrategy. Valid values are \"classpath\" and \"sources\""
          )
        case None =>
          val isClasspathEnabled = featureFlags
            .readBoolean(FeatureFlag.CLASSPATH_DEFINITION_INDEX)
            .orElse(false)
          if (isClasspathEnabled) {
            Right(DefinitionIndexStrategy.sources)
          } else {
            Right(DefinitionIndexStrategy.default)
          }
      }
    }
  }

  object TelemetryConfig {
    def default: TelemetryConfig =
      // NOTE: by default, Metals confusingly does not send telemetry even when
      // it's "enabled". You also have to make sure metals is running with a
      // classpath that registers service providers for FeatureFlagProvider and
      // MonitoringClient interfaces. A more accurate name for "enabled" would
      // be "enabled-if-instrumented" or something like that but it's still
      // confusing.
      // Databricks-only: our internal forks always instruments metals so the
      // "enabled" setting does in fact mean telemetry is enabled by default unless
      // you set -Dmetals.telemetry=disabled.
      new TelemetryConfig(System.getProperty("metals.telemetry", "enabled"))
  }

  final class TelemetryConfig(val value: String) {
    def isAllEnabled: Boolean =
      value == "enabled"
    def isMetricsEnabled: Boolean =
      isAllEnabled || value.contains("metrics")
    def isFeatureFlagsEnabled: Boolean =
      isAllEnabled || value.contains("feature-flags")
  }

  object SourcePathConfig {

    def fromConfigOrFeatureFlag(
        value: Option[String],
        featureFlags: FeatureFlagProvider,
        default: SourcePathMode = SourcePathMode.DISABLED,
    ): Either[String, SourcePathMode] = {
      value.map(_.toLowerCase) match {
        case Some("full") => Right(SourcePathMode.FULL)
        case Some("disabled") => Right(SourcePathMode.DISABLED)
        case Some("pruned") => Right(SourcePathMode.PRUNED)
        case Some(invalid) =>
          Left(
            s"invalid config value '$invalid' for source path. Valid values are \"full\", \"disabled\", and \"pruned\""
          )
        case None =>
          val isPrunedEnabled = featureFlags
            .readBoolean(FeatureFlag.SCALA_SOURCEPATH_PRUNED)
            .orElse(false)
          if (isPrunedEnabled) {
            scribe.debug(
              s"Overriding source path mode via Feature Flag to: PRUNED"
            )
            Right(SourcePathMode.PRUNED)
          } else {
            scribe.debug(s"Leaving default source path mode: $default")
            Right(default)
          }
      }
    }
  }
}
