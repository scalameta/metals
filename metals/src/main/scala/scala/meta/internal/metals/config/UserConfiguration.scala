package scala.meta.internal.metals.config

import java.util.Properties

import scala.collection.mutable.LinkedHashMap
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.control.NonFatal

import scala.meta.infra.FeatureFlagProvider
import scala.meta.internal.infra.NoopFeatureFlagProvider
import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.ClientConfiguration
import scala.meta.internal.metals.Directories
import scala.meta.internal.metals.InlayHintsOptions
import scala.meta.internal.metals.JavaBinary
import scala.meta.internal.metals.JavaFormatterConfig
import scala.meta.internal.metals.JsonParser.XtensionSerializedJson
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.config.CompilerProgressConfig
import scala.meta.internal.metals.config.DefinitionProviderConfig
import scala.meta.internal.metals.config.EclipseFormatConfig
import scala.meta.internal.metals.config.FallbackClasspathConfig
import scala.meta.internal.metals.config.FallbackSourcepathConfig
import scala.meta.internal.metals.config.JavaSymbolLoaderConfig
import scala.meta.internal.metals.config.JavacServicesOverrides
import scala.meta.internal.metals.config.MbtConfig
import scala.meta.internal.metals.config.ReferenceProviderConfig
import scala.meta.internal.metals.config.TurbineRecompileDelayConfig
import scala.meta.internal.metals.config.WorkspaceSymbolProviderConfig
import scala.meta.internal.metals.config.option.ConfigContext
import scala.meta.internal.metals.config.option.ConfigurationOption
import scala.meta.internal.metals.config.option.CustomConfigurationOption
import scala.meta.internal.metals.config.option.ObjectConfigurationOption
import scala.meta.internal.metals.config.option.UserConfigurationOption
import scala.meta.io.AbsolutePath
import scala.meta.pc.PresentationCompilerConfig
import scala.meta.pc.PresentationCompilerConfig.ScalaImportsPlacement

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject

/**
 * Configuration that the user can override via workspace/didChangeConfiguration.
 *
 * Settings are declared in [[UserConfigurationOptions]]. Each one knows how to
 * read itself from JSON, how to appear in [[toString]], and
 * how to show up in `metals config`.
 */
case class UserConfiguration(
    javaHome: Option[String] = None,
    sbtScript: Option[String] = None,
    gradleScript: Option[String] = None,
    mavenScript: Option[String] = None,
    millScript: Option[String] = None,
    scalafmtConfigPath: Option[AbsolutePath] = None,
    scalafixConfigPath: Option[AbsolutePath] = None,
    symbolPrefixes: Map[String, String] =
      PresentationCompilerConfig.defaultSymbolPrefixes().asScala.toMap,
    shimGlobs: Map[String, List[String]] = UserConfiguration.defaultShimGlobs,
    worksheetCancelTimeout: Int = 4,
    bloopSbtAlreadyInstalled: Boolean = false,
    bloopVersion: Option[String] = None,
    bloopJvmProperties: BloopJvmProperties = BloopJvmProperties.Uninitialized,
    superMethodLensesEnabled: Boolean = false,
    gotoTestLensesEnabled: Boolean = true,
    inlayHints: InlayHintsOptions = InlayHintsOptions.none,
    enableStripMarginOnTypeFormatting: Boolean = true,
    enableIndentOnPaste: Boolean = false,
    rangeFormattingProviders: List[String] = List("scalafmt"),
    excludedPackages: Option[List[String]] = None,
    fallbackScalaVersion: Option[String] = None,
    fallbackClasspath: FallbackClasspathConfig =
      FallbackClasspathConfig.Default,
    fallbackSourcepath: FallbackSourcepathConfig =
      FallbackSourcepathConfig.AllSources,
    testUserInterface: TestUserInterfaceKind = TestUserInterfaceKind.CodeLenses,
    eclipseFormat: EclipseFormatConfig = EclipseFormatConfig.default,
    javaFormatter: JavaFormatterConfig = JavaFormatterConfig.GoogleJavaFormat,
    scalafixRulesDependencies: List[String] = Nil,
    scalafixLintEnabled: Boolean = false,
    customProjectRoot: Option[String] = None,
    verboseCompilation: Boolean = false,
    autoImportBuild: AutoImportBuildKind = AutoImportBuildKind.Off,
    targetBuildTool: TargetBuildTool = TargetBuildTool.None,
    scalaCliLauncher: Option[String] = None,
    scalaCliEnabled: Boolean = false,
    defaultBspToBuildTool: Boolean = false,
    presentationCompilerDiagnostics: Boolean = true,
    buildChangedAction: BuildChangedAction = BuildChangedAction.None,
    buildOnChange: Boolean = false,
    buildOnFocus: Boolean = false,
    preferredBuildServer: Option[String] = None,
    useSourcePath: Boolean = true,
    workspaceSymbolProvider: WorkspaceSymbolProviderConfig =
      WorkspaceSymbolProviderConfig.MBT,
    definitionProviders: DefinitionProviderConfig =
      DefinitionProviderConfig.All,
    javaSymbolLoader: JavaSymbolLoaderConfig =
      JavaSymbolLoaderConfig.TurbineClasspath,
    javaTurbineRecompileDelay: TurbineRecompileDelayConfig =
      TurbineRecompileDelayConfig.default,
    javaTurbineCache: Boolean = false,
    javacServicesOverrides: JavacServicesOverrides =
      JavacServicesOverrides.default,
    compilerProgress: CompilerProgressConfig = CompilerProgressConfig.Enabled,
    referenceProvider: ReferenceProviderConfig = ReferenceProviderConfig.MBT,
    additionalPcChecks: List[String] = Nil,
    scalaImportsPlacement: ScalaImportsPlacement =
      PresentationCompilerConfig.ScalaImportsPlacement.SMART,
    batchSemanticdbCompilerInstances: Int = 1,
    promptBuildImport: Boolean = true,
    protobufLspEnabled: Boolean = true,
    enableBestEffort: Boolean = false,
    defaultShell: Option[String] = None,
    startMcpServer: Boolean = false,
    mcpClient: Option[String] = None,
    mbt: MbtConfig = MbtConfig.default,
) {

  def isMbtDefinitionProviderEnabled: Boolean =
    definitionProviders.isMBT(javaSymbolLoader)

  def isScalafmtRangeFormatterEnabled: Boolean =
    rangeFormattingProviders.contains("scalafmt")

  def isRefchecksEnabled: Boolean =
    additionalPcChecks.contains("refchecks")

  override def toString(): String = {
    val fields = LinkedHashMap.empty[String, Any]
    for (option <- UserConfiguration.settings)
      option.jsonEntry(this).foreach { case (key, value) =>
        fields.put(key, value)
      }
    val gson = new GsonBuilder().setPrettyPrinting().create()
    gson.toJson(fields.asJava).toString()
  }

  def shouldAutoImportNewProject: Boolean =
    autoImportBuild != AutoImportBuildKind.Off

  def currentBloopVersion: String =
    bloopVersion.getOrElse(BuildInfo.bloopVersion)

  def usedJavaBinary(): Option[AbsolutePath] = JavaBinary.path(javaHome)

  def areSyntheticsEnabled(): Boolean = inlayHints.areSyntheticsEnabled()

  def getCustomProjectRoot(workspace: AbsolutePath): Option[AbsolutePath] =
    customProjectRoot
      .map(relativePath => workspace.resolve(relativePath.trim()))
      .filter { projectRoot =>
        val exists = projectRoot.toFile.exists
        if (!exists) {
          scribe.error(s"custom project root $projectRoot does not exist")
        }
        exists
      }

}

object UserConfiguration {

  def default: UserConfiguration = UserConfiguration()
  val defaultShimGlobs: Map[String, List[String]] =
    Map.empty

  def settings: List[UserConfigurationOption[_]] =
    UserConfigurationOptions.settings

  def listOptions: String = {
    def oneLiners(
        option: ConfigurationOption[_, _],
        prefix: String,
    ): List[String] =
      option match {
        case o: CustomConfigurationOption[_] if o.subFields.nonEmpty =>
          o.subFields.map(_.oneLiner)
        case o: ObjectConfigurationOption[_] =>
          val nestedPrefix = s"$prefix${o.key}."
          o.subFields.flatMap(oneLiners(_, nestedPrefix))
        case other =>
          List(other.oneLiner(s"$prefix${other.key}"))
      }

    settings.flatMap(oneLiners(_, "")).mkString("\n")
  }

  def fromJson(
      json: JsonObject,
      clientConfiguration: ClientConfiguration,
      properties: Properties = System.getProperties,
      featureFlags: FeatureFlagProvider = NoopFeatureFlagProvider,
  ): Either[List[String], UserConfiguration] = {
    val context = new ConfigContext(
      json,
      properties,
      clientConfiguration,
      featureFlags,
    )
    val config = settings.foldLeft(UserConfiguration()) { (current, option) =>
      option.update(context, current)
    }
    val errors = context.errors
    if (errors.isEmpty) Right(config)
    else Left(errors)
  }

  def load(
      folder: AbsolutePath,
      clientConfiguration: ClientConfiguration,
  ): Option[UserConfiguration] = {
    val path = folder.resolve(Directories.userConfig)
    path.readTextOpt.flatMap { text =>
      Try(parse(text)) match {
        case Failure(error) =>
          scribe.warn(s"Failed to parse persisted user configuration: $error")
          None
        case Success(json) =>
          fromJson(json, clientConfiguration) match {
            case Left(errors) =>
              errors.foreach { error =>
                scribe.warn(s"Persisted user configuration error: $error")
              }
              None
            case Right(config) => Some(config)
          }
      }
    }
  }

  def save(folder: AbsolutePath, config: UserConfiguration): Unit =
    try folder.resolve(Directories.userConfig).writeText(config.toString())
    catch {
      case NonFatal(error) =>
        scribe.warn(s"Failed to persist user configuration: $error")
    }

  def parse(config: String): JsonObject = {
    // import JsonParser._
    config.parseJson.getAsJsonObject
  }

}
