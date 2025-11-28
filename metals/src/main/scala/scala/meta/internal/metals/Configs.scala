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
          new FileSystemWatcher(JEither.forLeft(s"$root/**/*.scala")),
          new FileSystemWatcher(JEither.forLeft(s"$root/**/*.java")),
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
    def mbt: WorkspaceSymbolProviderConfig =
      WorkspaceSymbolProviderConfig("mbt")
    def mbt2: WorkspaceSymbolProviderConfig =
      WorkspaceSymbolProviderConfig("mbt-v2")
    def bsp: WorkspaceSymbolProviderConfig =
      WorkspaceSymbolProviderConfig("bsp")
    def default: WorkspaceSymbolProviderConfig = bsp
    def fromConfigOrFeatureFlag(
        value: Option[String],
        featureFlags: FeatureFlagProvider,
    ): Either[String, WorkspaceSymbolProviderConfig] = {
      value match {
        case Some("mbt") if !LMDB.isSupportedOrWarn() =>
          Right(WorkspaceSymbolProviderConfig.bsp)
        case Some(ok @ ("bsp" | "mbt" | "mbt-v2")) =>
          Right(WorkspaceSymbolProviderConfig(ok))
        case Some(invalid) =>
          Left(
            s"invalid config value '$invalid' for workspaceSymbolProvider. Valid values are \"bsp\" and \"mbt\""
          )
        case None =>
          // The config is not explicitly set so fallback to the default, which
          // can optionally be overridden by a feature flag
          val isMbtV2Enabled = featureFlags
            .readBoolean(FeatureFlag.MBT_V2_SYMBOL_INDEX)
            .orElse(false)
          if (isMbtV2Enabled) {
            Right(WorkspaceSymbolProviderConfig.mbt2)
          } else {
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
  }
  @nowarn
  final case class WorkspaceSymbolProviderConfig private (val value: String) {
    if (!List("bsp", "mbt", "mbt-v2").contains(value)) {
      throw new IllegalArgumentException(
        s"only bsp or mbt are accepted, got $value"
      )
    }

    def isMBT: Boolean =
      value.startsWith("mbt")
    def isMBT1: Boolean =
      value == "mbt" // New BSP-free workspace/symbol implementation
    def isMBT2: Boolean =
      value == "mbt-v2"
    def isBSP: Boolean =
      value == "bsp" // The classic BSP-based workspace/symbol implementation
  }

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
            Right(DefinitionIndexStrategy.classpath)
          } else {
            Right(DefinitionIndexStrategy.default)
          }
      }
    }
  }

  final case class RangeFormattingProviders(val values: List[String]) {
    values.foreach(value =>
      require(
        RangeFormattingProviders.isValid(value),
        s"invalid value $value for range formatting providers. Valid values are \"scalafmt\"",
      )
    )
    def isScalafmt: Boolean =
      values.contains("scalafmt")
  }

  object RangeFormattingProviders {
    def isValid(value: String): Boolean =
      value == "scalafmt"
    def default: RangeFormattingProviders = RangeFormattingProviders(Nil)
    def fromConfigOrFeatureFlag(
        values: Option[List[String]],
        featureFlags: FeatureFlagProvider,
    ): Either[String, RangeFormattingProviders] = {
      values match {
        case Some(ok) if ok.forall(RangeFormattingProviders.isValid) =>
          Right(RangeFormattingProviders(ok))
        case Some(invalid) =>
          Left(
            s"invalid config value '$invalid' for range formatting providers. Valid values are \"scalafmt\""
          )
        case None =>
          val isScalafmtEnabled = featureFlags
            .readBoolean(FeatureFlag.SCALAFMT_RANGE_FORMATTER)
            .orElse(false)
          if (isScalafmtEnabled) {
            Right(RangeFormattingProviders(List("scalafmt")))
          } else {
            Right(RangeFormattingProviders.default)
          }
      }
    }
  }

  final case class ReferenceProviderConfig(val value: String) {
    require(List("bsp", "mbt").contains(value), value)
    val isBsp: Boolean =
      value == "bsp"
    val isMbt: Boolean =
      value == "mbt"
  }

  object ReferenceProviderConfig {
    def bsp: ReferenceProviderConfig =
      ReferenceProviderConfig("bsp")
    def mbt: ReferenceProviderConfig =
      ReferenceProviderConfig("mbt")
    def default: ReferenceProviderConfig = bsp
    def fromConfigOrFeatureFlag(
        value: Option[String],
        featureFlags: FeatureFlagProvider,
    ): Either[String, ReferenceProviderConfig] = {
      value match {
        case Some(ok @ ("bsp" | "mbt")) => Right(ReferenceProviderConfig(ok))
        case Some(invalid) =>
          Left(
            s"invalid config value '$invalid' for referenceProvider. Valid values are \"bsp\" and \"mbt\""
          )
        case None =>
          val isMbtEnabled = featureFlags
            .readBoolean(FeatureFlag.MBT_REFERENCE_PROVIDER)
            .orElse(false)
          if (isMbtEnabled) {
            Right(ReferenceProviderConfig.mbt)
          } else {
            Right(ReferenceProviderConfig.default)
          }
      }
    }
  }

  final case class JavaOutlineProviderConfig(val value: String) {
    require(List("qdox", "javac").contains(value), value)
    def isQdox: Boolean =
      value == "qdox"
    def isJavac: Boolean =
      value == "javac"
  }

  object JavaOutlineProviderConfig {
    def qdox: JavaOutlineProviderConfig =
      JavaOutlineProviderConfig("qdox")
    def javac: JavaOutlineProviderConfig =
      JavaOutlineProviderConfig("javac")
    def default: JavaOutlineProviderConfig = qdox
    def fromConfigOrFeatureFlag(
        value: Option[String],
        featureFlags: FeatureFlagProvider,
    ): Either[String, JavaOutlineProviderConfig] = {
      value match {
        case Some(ok @ ("qdox" | "javac")) =>
          Right(JavaOutlineProviderConfig(ok))
        case Some(invalid) =>
          Left(
            s"invalid config value '$invalid' for javaOutlineProvider. Valid values are \"qdox\" and \"javac\""
          )
        case None =>
          val isJavacEnabled = featureFlags
            .readBoolean(FeatureFlag.JAVAC_OUTLINE_PROVIDER)
            .orElse(false)
          if (isJavacEnabled) {
            Right(JavaOutlineProviderConfig.javac)
          } else {
            Right(JavaOutlineProviderConfig.default)
          }
      }
    }
  }

  final case class CompilerProgressConfig(val value: String) {
    require(List("enabled", "disabled").contains(value), value)
    def isEnabled: Boolean =
      value == "enabled"
    def isDisabled: Boolean =
      value == "disabled"
  }

  object CompilerProgressConfig {
    def enabled: CompilerProgressConfig =
      CompilerProgressConfig("enabled")
    def disabled: CompilerProgressConfig =
      CompilerProgressConfig("disabled")
    // NOTE(olafurpg): it's unclear if we ever want to enable it by default.
    // For now, this setting is incredibly helpful to debug Java PC performance
    // issues so I want to have the functionality in Metals even if users are
    // not using it by default. It might be helpful for users to troubleshoot
    // performance issues on their projects.
    def default: CompilerProgressConfig = disabled
    def fromConfigOrFeatureFlag(
        value: Option[String],
        featureFlags: FeatureFlagProvider,
    ): Either[String, CompilerProgressConfig] = {
      value match {
        case Some(ok @ ("enabled" | "disabled")) =>
          Right(CompilerProgressConfig(ok))
        case Some(invalid) =>
          Left(
            s"invalid config value '$invalid' for compilerProgress. Valid values are \"enabled\" and \"disabled\""
          )
        case None =>
          val isEnabled = featureFlags
            .readBoolean(FeatureFlag.COMPILE_PROGRESS)
            .orElse(false)
          if (isEnabled) {
            Right(CompilerProgressConfig.enabled)
          } else {
            Right(CompilerProgressConfig.default)
          }
      }
    }
  }

  final case class FallbackClasspathConfig(val values: List[String]) {
    values.foreach(value =>
      require(
        FallbackClasspathConfig.isValid(value),
        s"invalid value $value for fallbackClasspath. Valid values are \"all-3rdparty\" and \"guessed\"",
      )
    )
    def isAll3rdparty: Boolean =
      values.contains("all-3rdparty")
    def isGuessed: Boolean =
      values.contains("guessed")
  }

  object FallbackClasspathConfig {
    // We may want to support richer settings here in the future. For
    // example, defining dependencies inline like
    // "dependency:com.google:guava:VERSION" or "file://path/to/some.jar".
    def isValid(value: String): Boolean =
      value == "all-3rdparty" || value == "guessed"
    def all3rdparty: FallbackClasspathConfig =
      FallbackClasspathConfig(List("all-3rdparty"))
    def guessed: FallbackClasspathConfig =
      FallbackClasspathConfig(List("guessed"))

    def default: FallbackClasspathConfig = FallbackClasspathConfig(Nil)
    def fromConfigOrFeatureFlag(
        values: Option[List[String]],
        featureFlags: FeatureFlagProvider,
    ): Either[String, FallbackClasspathConfig] = {
      values match {
        case Some(ok) =>
          val invalid = ok.filterNot(FallbackClasspathConfig.isValid)
          if (invalid.nonEmpty) {
            Left(
              s"invalid config value '$invalid' for fallbackClasspath. Valid values are \"all-3rdparty\""
            )
          } else {
            Right(FallbackClasspathConfig(ok))
          }
        case Some(invalid) =>
          Left(
            s"invalid config value '$invalid' for fallbackClasspath. Valid values are \"all-3rdparty\""
          )
        case None =>
          val isAll3rdpartyEnabled = featureFlags
            .readBoolean(FeatureFlag.FALLBACK_CLASSPATH_ALL_3RD_PARTY)
            .orElse(false)
          if (isAll3rdpartyEnabled) {
            Right(FallbackClasspathConfig.all3rdparty)
          } else {
            Right(FallbackClasspathConfig.default)
          }
      }
    }
  }

  final case class FallbackSourcepathConfig(val value: String) {
    require(List("all-sources", "none").contains(value), value)
    def isAllSources: Boolean =
      value == "all-sources"
    def isNone: Boolean =
      value == "none"

    def enabled: Boolean =
      isAllSources
  }

  object FallbackSourcepathConfig {
    def default: FallbackSourcepathConfig = FallbackSourcepathConfig("none")
    def fromConfigOrFeatureFlag(
        value: Option[String],
        featureFlags: FeatureFlagProvider,
    ): Either[String, FallbackSourcepathConfig] = {
      value match {
        case Some("all-sources") =>
          Right(FallbackSourcepathConfig("all-sources"))
        case Some("none") =>
          Right(FallbackSourcepathConfig("none"))
        case Some(invalid) =>
          Left(
            s"invalid config value '$invalid' for fallbackSourcepath. Valid values are \"all-sources\" and \"none\""
          )
        case None =>
          val isEnabled = featureFlags
            .readBoolean(FeatureFlag.FULL_SOURCEPATH_FALLBACK_SCALA)
            .orElse(false)
          if (isEnabled) {
            Right(FallbackSourcepathConfig("all-sources"))
          } else {
            Right(FallbackSourcepathConfig.default)
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
