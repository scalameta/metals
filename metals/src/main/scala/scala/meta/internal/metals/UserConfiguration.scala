package scala.meta.internal.metals

import java.util.Properties

import scala.collection.mutable.LinkedHashMap
import scala.collection.mutable.ListBuffer
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import scala.meta.infra.FeatureFlag
import scala.meta.infra.FeatureFlagProvider
import scala.meta.internal.infra.NoopFeatureFlagProvider
import scala.meta.internal.metals.Configs.AdditionalPcChecksConfig
import scala.meta.internal.metals.Configs.BatchSemanticdbConfig
import scala.meta.internal.metals.Configs.CompilerProgressConfig
import scala.meta.internal.metals.Configs.DefinitionIndexStrategy
import scala.meta.internal.metals.Configs.DefinitionProviderConfig
import scala.meta.internal.metals.Configs.FallbackClasspathConfig
import scala.meta.internal.metals.Configs.FallbackSourcepathConfig
import scala.meta.internal.metals.Configs.JavaOutlineProviderConfig
import scala.meta.internal.metals.Configs.JavaSymbolLoaderConfig
import scala.meta.internal.metals.Configs.JavacServicesOverrides
import scala.meta.internal.metals.Configs.ProtoOutlineProviderConfig
import scala.meta.internal.metals.Configs.ProtobufLspConfig
import scala.meta.internal.metals.Configs.RangeFormattingProviders
import scala.meta.internal.metals.Configs.ReferenceProviderConfig
import scala.meta.internal.metals.Configs.ScalaImportsPlacementConfig
import scala.meta.internal.metals.Configs.TurbineRecompileDelayConfig
import scala.meta.internal.metals.Configs.WorkspaceSymbolProviderConfig
import scala.meta.internal.metals.JsonParser.XtensionSerializedAsOption
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.Symbol
import scala.meta.io.AbsolutePath
import scala.meta.pc.PresentationCompilerConfig
import scala.meta.pc.PresentationCompilerConfig.ScalaImportsPlacement

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

case class JavaFormatConfig(
    eclipseFormatConfigPath: AbsolutePath,
    eclipseFormatProfile: Option[String],
)

/**
 * Configuration that the user can override via workspace/didChangeConfiguration.
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
    worksheetScreenWidth: Int = 120,
    worksheetCancelTimeout: Int = 4,
    bloopSbtAlreadyInstalled: Boolean = false,
    bloopVersion: Option[String] = None,
    bloopJvmProperties: BloopJvmProperties = BloopJvmProperties.Uninitialized,
    superMethodLensesEnabled: Boolean = false,
    gotoTestLensesEnabled: Boolean = true,
    inlayHintsOptions: InlayHintsOptions = InlayHintsOptions(Map.empty),
    enableStripMarginOnTypeFormatting: Boolean = true,
    enableIndentOnPaste: Boolean = false,
    rangeFormattingProviders: RangeFormattingProviders =
      RangeFormattingProviders.default,
    enableSemanticHighlighting: Boolean = true,
    excludedPackages: Option[List[String]] = None,
    fallbackScalaVersion: Option[String] = None,
    fallbackClasspath: FallbackClasspathConfig =
      FallbackClasspathConfig.default,
    fallbackSourcepath: FallbackSourcepathConfig =
      FallbackSourcepathConfig.default,
    testUserInterface: TestUserInterfaceKind = TestUserInterfaceKind.CodeLenses,
    javaFormatConfig: Option[JavaFormatConfig] = None,
    javaFormatter: Option[JavaFormatterConfig] = None,
    scalafixRulesDependencies: List[String] = Nil,
    customProjectRoot: Option[String] = None,
    verboseCompilation: Boolean = false,
    automaticImportBuild: AutoImportBuildKind = AutoImportBuildKind.Off,
    scalaCliLauncher: Option[String] = None,
    scalaCliEnabled: Boolean = false,
    defaultBspToBuildTool: Boolean = false,
    presentationCompilerDiagnostics: Boolean = true,
    buildChangedAction: BuildChangedAction = BuildChangedAction.default,
    buildOnChange: Boolean = false,
    buildOnFocus: Boolean = false,
    preferredBuildServer: Option[String] = None,
    useSourcePath: Boolean = true,
    workspaceSymbolProvider: WorkspaceSymbolProviderConfig =
      WorkspaceSymbolProviderConfig.default,
    definitionProviders: DefinitionProviderConfig =
      DefinitionProviderConfig.default,
    definitionIndexStrategy: DefinitionIndexStrategy =
      DefinitionIndexStrategy.default,
    javaOutlineProvider: JavaOutlineProviderConfig =
      JavaOutlineProviderConfig.default,
    protoOutlineProvider: ProtoOutlineProviderConfig =
      ProtoOutlineProviderConfig.default,
    javaSymbolLoader: JavaSymbolLoaderConfig = JavaSymbolLoaderConfig.default,
    javaTurbineRecompileDelay: TurbineRecompileDelayConfig =
      TurbineRecompileDelayConfig.default,
    javacServicesOverrides: JavacServicesOverrides =
      JavacServicesOverrides.default,
    compilerProgress: CompilerProgressConfig = CompilerProgressConfig.default,
    referenceProvider: ReferenceProviderConfig =
      ReferenceProviderConfig.default,
    additionalPcChecks: AdditionalPcChecksConfig =
      AdditionalPcChecksConfig.default,
    scalaImportsPlacement: ScalaImportsPlacement =
      ScalaImportsPlacementConfig.default,
    batchSemanticdbCompilerInstances: BatchSemanticdbConfig =
      BatchSemanticdbConfig.default,
    promptBuildImport: Boolean = true,
    protobufLspConfig: ProtobufLspConfig = ProtobufLspConfig.default,
    enableBestEffort: Boolean = false,
    defaultShell: Option[String] = None,
    startMcpServer: Boolean = false,
    mcpClient: Option[String] = None,
) {

  def isMbtDefinitionProviderEnabled: Boolean =
    definitionProviders.isMBT(javaSymbolLoader)

  override def toString(): String = {
    def mapField[K, T](
        key: String,
        opts: Map[K, T],
    ): Option[(String, java.util.Map[String, String])] = {
      val serializable = opts.map { case (k, v) =>
        (k.toString(), v.toString())
      }
      Some((key, serializable.asJava))
    }
    def mapListField[K, T](
        key: String,
        opts: Map[K, List[T]],
    ): Option[(String, java.util.Map[String, java.util.List[String]])] = {
      val serializable = opts.map { case (k, values) =>
        (k.toString(), values.map(_.toString()).asJava)
      }
      Some((key, serializable.asJava))
    }

    def listField[T](key: String, list: Option[List[String]]) = {
      list match {
        case None => None
        case Some(value) => Some(key -> value.asJava)
      }
    }

    def optStringField(
        key: String,
        value: Option[Any],
    ): Option[(String, Any)] =
      value match {
        case None => None
        case Some(value) => Some(key -> value.toString)
      }

    val fields = LinkedHashMap.from[String, Any](
      List(
        optStringField("javaHome", javaHome),
        optStringField("sbtScript", sbtScript),
        optStringField("gradleScript", gradleScript),
        optStringField("mavenScript", mavenScript),
        optStringField("millScript", millScript),
        optStringField("scalafmtConfigPath", scalafmtConfigPath),
        optStringField("scalafixConfigPath", scalafixConfigPath),
        optStringField("scalafixConfigPath", scalafixConfigPath),
        mapField("symbolPrefixes", symbolPrefixes),
        mapListField("shimGlobs", shimGlobs),
        Some(("worksheetScreenWidth", worksheetScreenWidth)),
        Some(("worksheetCancelTimeout", worksheetCancelTimeout)),
        Some(("bloopSbtAlreadyInstalled", bloopSbtAlreadyInstalled)),
        optStringField("bloopVersion", bloopVersion),
        listField("bloopJvmProperties", bloopJvmProperties.properties),
        Some(("superMethodLensesEnabled", superMethodLensesEnabled)),
        Some(("gotoTestLensesEnabled", gotoTestLensesEnabled)),
        mapField("inlayHintsOptions", inlayHintsOptions.options),
        Some(
          (
            "enableStripMarginOnTypeFormatting",
            enableStripMarginOnTypeFormatting,
          )
        ),
        Some(("enableIndentOnPaste", enableIndentOnPaste)),
        listField(
          "rangeFormattingProviders",
          Some(rangeFormattingProviders.values),
        ),
        Some(
          (
            "enableSemanticHighlighting",
            enableSemanticHighlighting,
          )
        ),
        listField("excludedPackages", excludedPackages),
        optStringField("fallbackScalaVersion", fallbackScalaVersion),
        listField("fallbackClasspath", Some(fallbackClasspath.values)),
        optStringField("fallbackSourcepath", Some(fallbackSourcepath.value)),
        Some("testUserInterface" -> testUserInterface.toString()),
        javaFormatConfig.map(value =>
          "javaFormat" -> List(
            Some(
              "eclipseConfigPath" -> value.eclipseFormatConfigPath.toString()
            ),
            value.eclipseFormatProfile.map("eclipseProfile" -> _),
          ).flatten.toMap.asJava
        ),
        optStringField("javaFormatter", javaFormatter.map(_.value)),
        listField(
          "scalafixRulesDependencies",
          Some(scalafixRulesDependencies),
        ),
        optStringField("customProjectRoot", customProjectRoot),
        Some(("verboseCompilation", verboseCompilation)),
        Some(
          "autoImportBuilds" ->
            automaticImportBuild.toString().toLowerCase()
        ),
        optStringField("scalaCliLauncher", scalaCliLauncher),
        Some(("scalaCliEnabled", scalaCliEnabled)),
        Some(
          (
            "defaultBspToBuildTool",
            defaultBspToBuildTool,
          )
        ),
        Some(
          "presentationCompilerDiagnostics",
          presentationCompilerDiagnostics,
        ),
        Some(
          "buildChangedAction" -> buildChangedAction.value
        ),
        Some(
          (
            "buildOnChange",
            buildOnChange,
          )
        ),
        Some(
          (
            "buildOnFocus",
            buildOnFocus,
          )
        ),
        optStringField(
          "preferredBuildServer",
          preferredBuildServer,
        ),
        Some(
          (
            "useSourcePath",
            useSourcePath,
          )
        ),
        Some(
          (
            "workspaceSymbolProvider",
            workspaceSymbolProvider.value,
          )
        ),
        Some(
          (
            "definitionProviders",
            definitionProviders.values.asJava,
          )
        ),
        Some(
          (
            "definitionIndexStrategy",
            definitionIndexStrategy.value,
          )
        ),
        Some(
          (
            "javaOutlineProvider",
            javaOutlineProvider.value,
          )
        ),
        Some(
          (
            "protoOutlineProvider",
            protoOutlineProvider.value,
          )
        ),
        Some(
          (
            "javaSymbolLoader",
            javaSymbolLoader.value,
          )
        ),
        Some(
          (
            "javaTurbineRecompileDelay",
            javaTurbineRecompileDelay.duration.toString(),
          )
        ),
        Some(
          (
            "javacServicesOverrides",
            javacServicesOverrides,
          )
        ),
        Some(
          (
            "compilerProgress",
            compilerProgress.value,
          )
        ),
        Some(
          (
            "referenceProvider",
            referenceProvider.value,
          )
        ),
        listField(
          "additionalPcChecks",
          Some(additionalPcChecks.values),
        ),
        Some(
          (
            "scalaImportsPlacement",
            scalaImportsPlacement.name().toLowerCase().replace("_", "-"),
          )
        ),
        Some(
          (
            "batchSemanticdbCompilerInstances",
            batchSemanticdbCompilerInstances.instances,
          )
        ),
        Some(
          (
            "promptBuildImport",
            promptBuildImport,
          )
        ),
        Some(
          (
            "protobufLsp",
            Map(
              "diagnostics" -> protobufLspConfig.diagnostics,
              "hover" -> protobufLspConfig.hover,
              "definition" -> protobufLspConfig.definition,
              "completions" -> protobufLspConfig.completions,
              "semanticTokens" -> protobufLspConfig.semanticTokens,
              "semanticdb" -> protobufLspConfig.semanticdb,
              "javaPackagePrefix" -> protobufLspConfig.javaPackagePrefix,
            ).asJava,
          )
        ),
        Some(
          (
            "enableBestEffort",
            enableBestEffort,
          )
        ),
        Some(
          (
            "startMcpServer",
            startMcpServer,
          )
        ),
        optStringField("mcpClient", mcpClient),
      ).flatten
    )
    val gson = new GsonBuilder().setPrettyPrinting().create()
    gson.toJson(fields.asJava).toString()
  }

  def shouldAutoImportNewProject: Boolean =
    automaticImportBuild != AutoImportBuildKind.Off

  def currentBloopVersion: String =
    bloopVersion.getOrElse(BuildInfo.bloopVersion)

  def usedJavaBinary(): Option[AbsolutePath] = JavaBinary.path(javaHome)

  def areSyntheticsEnabled(): Boolean = inlayHintsOptions.areSyntheticsEnabled

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

  private val defaultExclusion =
    ExcludedPackagesHandler.defaultExclusions
      .map(_.dropRight(1))
      .mkString("\n")
      .replace("/", ".")

  def options: List[UserConfigurationOption] =
    List(
      UserConfigurationOption(
        "java-home",
        "`JAVA_HOME` environment variable with fallback to `user.home` system property.",
        """"/Library/Java/JavaVirtualMachines/jdk1.8.0_192.jdk/Contents/Home"""",
        "Java Home directory",
        "The Java Home directory used for indexing JDK sources and locating the `java` binary.",
      ),
      UserConfigurationOption(
        "sbt-script",
        """empty string `""`.""",
        """"/usr/local/bin/sbt"""",
        "sbt script",
        """Optional absolute path to an `sbt` executable to use for running `sbt bloopInstall`.
          |By default, Metals uses `java -jar sbt-launch.jar` with an embedded launcher while respecting
          |`.jvmopts` and `.sbtopts`. Update this setting if your `sbt` script requires more customizations
          |like using environment variables.
          |""".stripMargin,
      ),
      UserConfigurationOption(
        "gradle-script",
        """empty string `""`.""",
        """"/usr/local/bin/gradle"""",
        "Gradle script",
        """Optional absolute path to a `gradle` executable to use for running `gradle bloopInstall`.
          |By default, Metals uses gradlew with 7.5.0 gradle version. Update this setting if your `gradle` script requires more customizations
          |like using environment variables.
          |""".stripMargin,
      ),
      UserConfigurationOption(
        "maven-script",
        """empty string `""`.""",
        """"/usr/local/bin/mvn"""",
        "Maven script",
        """Optional absolute path to a `maven` executable to use for generating bloop config.
          |By default, Metals uses mvnw maven wrapper with 3.6.1 maven version. Update this setting if your `maven` script requires more customizations
          |""".stripMargin,
      ),
      UserConfigurationOption(
        "mill-script",
        """empty string `""`.""",
        """"/usr/local/bin/mill"""",
        "Mill script",
        """Optional absolute path to a `mill` executable to use for running `mill mill.contrib.bloop.Bloop/install`.
          |By default, Metals uses mill wrapper script with 0.5.0 mill version. Update this setting if your `mill` script requires more customizations
          |like using environment variables.
          |""".stripMargin,
      ),
      UserConfigurationOption(
        "scalafmt-config-path",
        """empty string `""`.""",
        """"project/.scalafmt.conf"""",
        "Scalafmt config path",
        """Optional custom path to the .scalafmt.conf file.
          |It should be a path (relative or absolute - though an absolute path is recommended) and use
          |forward slashes `/` for file separators (even on Windows).
          |""".stripMargin,
      ),
      UserConfigurationOption(
        "scalafix-config-path",
        """empty string `""`.""",
        """"project/.scalafix.conf"""",
        "Scalafix config path",
        """Optional custom path to the .scalafix.conf file.
          |It should be a path (relative or absolute - though an absolute path is recommended) and use
          |forward slashes `/` for file separators (even on Windows).
          |""".stripMargin,
      ),
      UserConfigurationOption(
        "shim-globs",
        """`{}`.""",
        """{ "default": [] }""",
        "Shim file globs",
        """|Named groups of file glob patterns used to detect shim files in the presentation compiler.
           |Entries follow the 'glob' syntax of FileSystem.getPathMatcher, e.g. use `**/shims.scala` to
           |match all shims.scala files in the workspace.
           |Values from all groups are combined into a single list.
           |""".stripMargin,
      ),
      UserConfigurationOption(
        "excluded-packages",
        """`[]`.""",
        """["akka.actor.typed.javadsl"]""",
        "Excluded Packages",
        s"""|Packages that will be excluded from completions, imports, and symbol searches.
            |
            |Note that this is in addition to some default packages that are already excluded.
            |The default excluded packages are listed below:
            |```js
            |${defaultExclusion}
            |```
            |
            |If there is a need to remove one of the defaults, you are able to do so by including the
            |package in your list and prepending `--` to it.
            |
            |Example:
            |
            |```js
            |["--sun"]
            |```
            |""".stripMargin,
      ),
      UserConfigurationOption(
        "bloop-sbt-already-installed", "false", "false",
        "Don't generate Bloop plugin file for sbt",
        "If true, Metals will not generate `metals.sbt` files under the assumption that sbt-bloop is already manually installed in the sbt build. Build import will fail with a 'not valid command bloopInstall' error in case Bloop is not manually installed in the build when using this option.",
      ),
      UserConfigurationOption(
        "bloop-version",
        BuildInfo.bloopVersion,
        """"1.4.0-RC1"""",
        "Version of Bloop",
        """|This version will be used for the Bloop build tool plugin, for any supported build tool,
           |while importing in Metals as well as for running the embedded server""".stripMargin,
      ),
      UserConfigurationOption(
        "bloop-jvm-properties",
        """["-Xmx1G"].""",
        """["-Xmx1G"]""",
        "Bloop JVM Properties",
        """|Optional list of JVM properties to pass along to the Bloop server.
           |Please follow this guide for the format https://scalacenter.github.io/bloop/docs/server-reference#global-settings-for-the-server"
           |""".stripMargin,
      ),
      UserConfigurationOption(
        "super-method-lenses-enabled",
        "false",
        "false",
        "Should display lenses with links to super methods",
        """|Super method lenses are visible above methods definition that override another methods. Clicking on a lens jumps to super method definition.
           |Disabled lenses are not calculated for opened documents which might speed up document processing.
           |
           |""".stripMargin,
      ),
      UserConfigurationOption(
        "inlay-hints.inferred-types.enable",
        "false",
        "false",
        "Should display type annotations for inferred types",
        """|When this option is enabled, each method that can have inferred types has them
           |displayed either as additional decorations if they are supported by the editor or
           |shown in the hover.
           |""".stripMargin,
      ),
      UserConfigurationOption(
        "inlay-hints.named-parameters.enable",
        "false",
        "false",
        "Should display parameter names next to arguments",
        """|When this option is enabled, each method has an added parameter name next to its arguments
           |displayed either as additional decorations if they are supported by the editor or 
           |shown in the hover.
           |""".stripMargin,
      ),
      UserConfigurationOption(
        "inlay-hints.by-name-parameters.enable",
        "false",
        "false",
        "Should display if a parameter is by-name at usage sites",
        """|When this option is enabled, each method that has by-name parameters has them 
           |displayed either as additional '=>' decorations if they are supported by the editor or 
           |shown in the hover.
           |""".stripMargin,
      ),
      UserConfigurationOption(
        "inlay-hints.implicit-arguments.enable",
        "false",
        "false",
        "Should display implicit parameter at usage sites",
        """|When this option is enabled, each method that has implicit arguments has them
           |displayed either as additional decorations if they are supported by the editor or
           |shown in the hover.
           |""".stripMargin,
      ),
      UserConfigurationOption(
        "inlay-hints.implicit-conversions.enable",
        "false",
        "false",
        "Should display implicit conversion at usage sites",
        """|When this option is enabled, each place where an implicit method or class is used has it
           |displayed either as additional decorations if they are supported by the editor or
           |shown in the hover.
           |""".stripMargin,
      ),
      UserConfigurationOption(
        "inlay-hints.type-parameters.enable",
        "false",
        "false",
        "Should display type annotations for type parameters",
        """|When this option is enabled, each place when a type parameter is applied has it
           |displayed either as additional decorations if they are supported by the editor or
           |shown in the hover.
           |""".stripMargin,
      ),
      UserConfigurationOption(
        "inlay-hints.hints-in-pattern-match.enable",
        "false",
        "false",
        "Should display type annotations in pattern matches",
        """|When this option is enabled, each place when a type is inferred in a pattern match has it
           |displayed either as additional decorations if they are supported by the editor or
           |shown in the hover.
           |""".stripMargin,
      ),
      UserConfigurationOption(
        "inlay-hints.hints-x-ray-mode.enable",
        "false",
        "false",
        "Should display type annotations for intermediate types of multi-line expressions",
        """|When this option is enabled, each method/attribute call in a multi-line chain will get
           | its own type annotation.
           |""".stripMargin,
      ),
      UserConfigurationOption(
        "enable-semantic-highlighting",
        "true",
        "false",
        "Use semantic tokens highlight",
        """|When this option is enabled, Metals will provide semantic tokens for clients that support it.
           |The feature should work within all supported files extensions aside from Java.
           |""".stripMargin,
      ),
      UserConfigurationOption(
        "enable-indent-on-paste",
        "false",
        "false",
        "Indent snippets when pasted.",
        """|When this option is enabled, when a snippet is pasted into a Scala file, Metals will
           |try to adjust the indentation to that of the current cursor.
           |""".stripMargin,
      ),
      UserConfigurationOption(
        "fallback-scala-version",
        BuildInfo.scala3,
        BuildInfo.scala3,
        "Default fallback Scala version",
        """|The Scala compiler version that is used as the default or fallback in case a file
           |doesn't belong to any build target or the specified Scala version isn't supported by Metals.
           |This applies to standalone Scala files, worksheets and Scala CLI scripts.
        """.stripMargin,
      ),
      UserConfigurationOption(
        "test-user-interface",
        "Code Lenses",
        "test explorer",
        "Test UI used for tests and test suites",
        """|Default way of handling tests and test suites.  The only valid values are
           |"code lenses" and "test explorer".  See https://scalameta.org/metals/docs/integrations/test-explorer
           |for information on how to work with the test explorer.
           |""".stripMargin,
      ),
      UserConfigurationOption(
        "java-format.eclipse-config-path",
        """empty string `""`.""",
        """"formatters/eclipse-formatter.xml"""",
        "Eclipse Java formatter config path",
        """Optional custom path to the eclipse-formatter.xml file.
          |It should be a path (relative or absolute - though an absolute path is recommended) and use
          |forward slashes `/` for file separators (even on Windows).
          |""".stripMargin,
      ),
      UserConfigurationOption(
        "java-format.eclipse-profile",
        """empty string `""`.""",
        """"GoogleStyle"""",
        "Eclipse Java formatting profile",
        """|If the Eclipse formatter file contains more than one profile, this option can be used to control which is used.
           |""".stripMargin,
      ),
      UserConfigurationOption(
        "java-formatter",
        """empty string `""`.""",
        """"google-java-format"""",
        "Java formatter",
        """|The Java formatter to use. Valid values are "eclipse", "google-java-format", or "none".
           |If "none" is specified, Java formatting will be disabled. If not specified, defaults to "google-java-format".
           |""".stripMargin,
      ),
      UserConfigurationOption(
        "scala-cli-launcher",
        """empty string `""`.""",
        """"/usr/local/bin/scala-cli"""",
        "Scala CLI launcher",
        """Optional absolute path to a `scala-cli` executable to use for running a Scala CLI BSP server.
          |By default, Metals uses the scala-cli from the PATH, or it's not found, downloads and runs Scala
          |CLI on the JVM (slower than native Scala CLI). Update this if you want to use a custom Scala CLI
          |launcher, not available in PATH.
          |""".stripMargin,
      ),
      UserConfigurationOption(
        "custom-project-root",
        """empty string `""`.""",
        """"backend/scalaProject/"""",
        "Custom project root",
        """Optional relative path to your project's root.
          |If you want your project root to be the workspace/workspace root set it to "." .""".stripMargin,
      ),
      UserConfigurationOption(
        "verbose-compilation",
        "false",
        "true",
        "Show all compilation debugging information",
        """|If a build server supports it (for example Bloop or Scala CLI), setting it to true
           |will make the logs contain all the possible debugging information including
           |about incremental compilation in Zinc.""".stripMargin,
      ),
      UserConfigurationOption(
        "auto-import-build",
        "off",
        "all",
        "Import build when changes detected without prompting",
        """|Automatically import builds rather than prompting the user to choose. "initial" will
           |only automatically import a build when a project is first opened, "all" will automate
           |build imports after subsequent changes as well.""".stripMargin,
      ),
      UserConfigurationOption(
        "default-bsp-to-build-tool",
        "false",
        "true",
        "Default to using build tool as your build server.",
        """|If your build tool can also serve as a build server,
           |default to using it instead of Bloop.
           |""".stripMargin,
      ),
      UserConfigurationOption(
        "presentation-compiler-diagnostics",
        "true",
        "false",
        "[Experimental] Show diagnostics messages from the Scala presentation compiler",
        """|Show presentation compiler errors and warnings as you type. This gives a
           |much faster feedback loop but may show incorrect or incomplete error messages. Only
           |supported in Scala 2.
           |""".stripMargin,
      ),
      UserConfigurationOption(
        "build-on-change",
        "true",
        "false",
        "Disable build-on-change",
        """|If enabled, Metals will not automatically build the project when a file changes.
           |""".stripMargin,
      ),
      UserConfigurationOption(
        "build-on-focus",
        "true",
        "false",
        "Disable build-on-focus",
        """|If enabled, Metals will not automatically build the project when a file is focused (opened).
           |""".stripMargin,
      ),
      UserConfigurationOption(
        "preferred-build-server",
        """empty string `""`.""",
        """"bazelbsp"""",
        "Preferred build server",
        """|If set, metals will prefer the specified build server when available instead
           |of prompting the user.
           |""".stripMargin,
      ),
      UserConfigurationOption(
        "use-source-path",
        "true",
        "true",
        "Use presentation compiler source path",
        """|If enabled, Metals will set the presentation compiler source path. This will enable
           |the compiler to find types that have not been built yet.
           |""".stripMargin,
      ),
      UserConfigurationOption(
        "workspace-symbol-provider",
        "bsp",
        "\"mbt\"",
        "Workspace Symbol Provider",
        """|The workspace symbol provider to use. The only valid values are "bsp" and "mbt".
           |- "bsp": The classic solution that only indexes sources from the BSP server.
           |- "mbt": A new BSP-free solution that indexes sources from the git repository.
           |""".stripMargin,
      ),
      UserConfigurationOption(
        "additional-pc-checks",
        "`[]`",
        """["refchecks"]""",
        "Additional Presentation Compiler Checks",
        """|A list of additional compiler phases to run in the presentation compiler.
           |Valid values are "refchecks". When "refchecks" is included, the
           |presentation compiler will run the RefChecks phase for additional
           |type checking diagnostics.
           |""".stripMargin,
      ),
      UserConfigurationOption(
        "prompt-build-import",
        "false",
        "true",
        "Prompt Build Import",
        """|If enabled, Metals will prompt you to import or connect to a build server
           |when a new workspace is detected. When disabled, you can still manually
           |trigger import via the "Import build" command.
           |""".stripMargin,
      ),
      UserConfigurationOption(
        "enable-best-effort",
        "false",
        "true",
        "Use best effort compilation for Scala 3.",
        """|When using Scala 3, use best effort compilation to improve Metals 
           |correctness when the workspace doesn't compile.
           |""".stripMargin,
      ),
      UserConfigurationOption(
        "default-shell",
        """empty string `""`.""",
        "/usr/bin/fish",
        "Full path to the shell executable to be used as the default",
        """|Optionally provide a default shell executable to use for build operations.
           |This allows customizing the shell environment before build execution.
           |When specified, must use absolute path to the shell.
           |The configured shell will be used for all build-related subprocesses.
           |""".stripMargin,
      ),
      UserConfigurationOption(
        "start-mcp-server",
        "false",
        "true",
        "Start MCP server",
        """|If Metals should start the MCP (SSE) server, that an AI agent can connect to.
           |""".stripMargin,
      ),
      UserConfigurationOption(
        "mcp-client",
        """empty string `""`.""",
        "claude",
        "MCP Client Name",
        """|This is used in situations where the client you're using doesn't match the editor
           |you're using or you also want an extra config generated, which is what Metals will
           |default to. For example if you use claude code cli in your terminal while you have
           |Metals running you would set this to "claude".
           |NOTE: This will generate an extra config if Metals supports the client you are passing in
           |and it will still generate the one matching your editor if it's also supported.
           |""".stripMargin,
      ),
    )

  def fromJson(
      json: JsonObject,
      clientConfiguration: ClientConfiguration,
      properties: Properties = System.getProperties,
      featureFlags: FeatureFlagProvider = NoopFeatureFlagProvider,
  ): Either[List[String], UserConfiguration] = {
    val errors = ListBuffer.empty[String]

    def getKey[A](
        key: String,
        currentObject: JsonObject,
        f: JsonElement => Option[A],
    ): Option[A] = {
      def option[T](fn: String => T): Option[T] =
        Option(fn(key)).orElse(Option(fn(StringCase.kebabToCamel(key))))
      for {
        jsonValue <- option(k => properties.getProperty(s"metals.$k"))
          .filterNot(_.isEmpty())
          .map(prop => new JsonPrimitive(prop))
          .orElse(option(currentObject.get))
        value <- f(jsonValue)
      } yield value
    }
    def getSubKey(key: String): Option[JsonObject] =
      getKey(
        key,
        json,
        { value =>
          Try(value.getAsJsonObject())
            .fold(
              _ => {
                errors += s"json error: key '$key' should have value of type object but obtained $value"
                None
              },
              Some(_),
            )
        },
      )
    def getParsedArrayKey[T](
        key: String,
        parse: Option[List[String]] => Either[String, T],
    ): Option[T] = {
      parse(getStringListKey(key)).fold(
        err => {
          errors += s"json error: $err"
          None
        },
        Some(_),
      )
    }
    def getParsedKey[T](
        key: String,
        parse: Option[String] => Either[String, T],
    ): Option[T] = parse(getStringKey(key)) match {
      case Left(err) =>
        errors += s"json error: $err"
        None
      case Right(ok) =>
        Some(ok)
    }
    def getStringKey(key: String): Option[String] =
      getStringKeyOnObj(key, json)

    def getStringKeyOnObj(
        key: String,
        currentObject: JsonObject,
    ): Option[String] =
      getKey(
        key,
        currentObject,
        { value =>
          Try(value.getAsString)
            .fold(
              _ => {
                errors += s"json error: key '$key' should have value of type string but obtained $value"
                None
              },
              Some(_),
            )
            .filter(_.nonEmpty)
        },
      )

    def getBooleanKey(key: String): Option[Boolean] =
      getKey(
        key,
        json,
        { value =>
          Try(value.getAsBoolean())
            .fold(
              _ => {
                errors += s"json error: key '$key' should have value of type boolean but obtained $value"
                None
              },
              Some(_),
            )
        },
      )
    def getIntKey(key: String): Option[Int] =
      getStringKey(key).flatMap { value =>
        Try(value.toInt) match {
          case Failure(_) =>
            errors += s"Not a number: '$value'"
            None
          case Success(value) =>
            Some(value)
        }
      }

    def getStringListKey(key: String): Option[List[String]] =
      getKey[List[String]](
        key,
        json,
        { elem =>
          if (elem.isJsonArray()) {
            val parsed = elem.getAsJsonArray().asScala.flatMap { value =>
              Try(value.getAsJsonPrimitive().getAsString()) match {
                case Failure(_) =>
                  errors += s"json error: values in '$key' should have value of type string but obtained $value"
                  None
                case Success(value) =>
                  Some(value)
              }
            }
            Some(parsed.toList)
          } else {
            errors += s"json error: key '$key' should have value of type array but obtained $elem"
            None
          }
        },
      )

    def getStringMap(key: String): Option[Map[String, String]] =
      getKey(
        key,
        json,
        { value =>
          Try {
            for {
              entry <- value.getAsJsonObject.entrySet().asScala.iterator
              if entry.getValue.isJsonPrimitive &&
                entry.getValue.getAsJsonPrimitive.isString
            } yield {
              entry.getKey -> entry.getValue.getAsJsonPrimitive.getAsString
            }
          }.fold(
            _ => {
              errors += s"json error: key '$key' should have be object with string values but obtained $value"
              None
            },
            entries => Some(entries.toMap),
          ).filter(_.nonEmpty)
        },
      )

    def getStringListMap(key: String): Option[Map[String, List[String]]] =
      getKey(
        key,
        json,
        { value =>
          Try {
            value.getAsJsonObject
              .entrySet()
              .asScala
              .map { entry =>
                val values = entry.getValue
                if (!values.isJsonArray) {
                  throw new IllegalArgumentException(
                    s"Expected array value for '${entry.getKey}'"
                  )
                }
                val strings = values.getAsJsonArray.asScala.map { elem =>
                  if (
                    !elem.isJsonPrimitive || !elem.getAsJsonPrimitive.isString
                  ) {
                    throw new IllegalArgumentException(
                      s"Expected string value in array for '${entry.getKey}'"
                    )
                  }
                  elem.getAsString
                }.toList
                entry.getKey -> strings
              }
              .toMap
          }.fold(
            _ => {
              errors += s"json error: key '$key' should have be object with array string values but obtained $value"
              None
            },
            entries => Some(entries),
          ).filter(_.nonEmpty)
        },
      )

    def getInlayHints =
      getKey(
        "inlay-hints",
        json,
        { value =>
          Try {
            for {
              entry <- value.getAsJsonObject.entrySet().asScala.iterator
              enable <- entry
                .getValue()
                .getAsJsonObject()
                .getBooleanOption("enable")
            } yield {
              entry.getKey -> enable
            }
          }.fold(
            _ => {
              errors += s"json error: key 'inlayHints' should have be object with Boolean values but obtained $value"
              None
            },
            entries => Some(entries.toMap),
          ).filter(_.nonEmpty)
        },
      )

    val javaHome =
      getStringKey("java-home")
    val scalafmtConfigPath =
      getStringKey("scalafmt-config-path")
        .map(AbsolutePath(_))
    val scalafixConfigPath =
      getStringKey("scalafix-config-path")
        .map(AbsolutePath(_))
    val sbtScript =
      getStringKey("sbt-script")
    val gradleScript =
      getStringKey("gradle-script")
    val mavenScript =
      getStringKey("maven-script")
    val millScript =
      getStringKey("mill-script")
    val symbolPrefixes =
      getStringMap("symbol-prefixes")
        .getOrElse(default.symbolPrefixes)
    val shimGlobs = {
      val userGlobs =
        getStringListMap("shim-globs").getOrElse(default.shimGlobs)
      val fflagGlobs =
        featureFlags.readStringList(FeatureFlag.SHIM_GLOBS).asScala.toList
      if (fflagGlobs.isEmpty) userGlobs
      else userGlobs + ("_default" -> fflagGlobs)
    }
    errors ++= symbolPrefixes.keys.flatMap { sym =>
      Symbol.validated(sym).left.toOption
    }
    val worksheetScreenWidth =
      getIntKey("worksheet-screen-width")
        .getOrElse(default.worksheetScreenWidth)
    val worksheetCancelTimeout =
      getIntKey("worksheet-cancel-timeout")
        .getOrElse(default.worksheetCancelTimeout)
    val bloopSbtAlreadyInstalled =
      getBooleanKey("bloop-sbt-already-installed").getOrElse(false)
    val bloopVersion =
      getStringKey("bloop-version")
    val defaultShell =
      getStringKey("default-shell")
    val bloopJvmProperties = getStringListKey("bloop-jvm-properties") match {
      case None => BloopJvmProperties.Empty
      case Some(props) => BloopJvmProperties.WithProperties(props)
    }
    val superMethodLensesEnabled =
      getBooleanKey("super-method-lenses-enabled").getOrElse(false)
    val gotoTestLensesEnabled =
      getBooleanKey("goto-test-lenses-enabled").getOrElse(true)

    // For old inlay hints settings
    def inlayHintsOptionsFallback: Map[InlayHintsOption, Boolean] = {
      val showInferredType =
        getStringKey("show-inferred-type")
      val inferredType = showInferredType.contains("true") ||
        showInferredType.contains("minimal")
      val typeParameters = showInferredType.contains("true")
      val implicitArguments =
        getBooleanKey("show-implicit-arguments").getOrElse(false)
      val implicitConversionsAndClasses =
        getBooleanKey("show-implicit-conversions-and-classes")
          .getOrElse(false)
      Map(
        InlayHintsOption.InferredType -> inferredType,
        InlayHintsOption.TypeParameters -> typeParameters,
        InlayHintsOption.ImplicitArguments -> implicitArguments,
        InlayHintsOption.ImplicitConversions -> implicitConversionsAndClasses,
      )
    }
    val inlayHintsOptions =
      InlayHintsOptions(getInlayHints match {
        case Some(options) =>
          options.collect { case (InlayHintsOption(key), value) =>
            key -> value
          }
        case _ => inlayHintsOptionsFallback
      })

    val enableStripMarginOnTypeFormatting =
      getBooleanKey("enable-strip-margin-on-type-formatting").getOrElse(true)
    val enableIndentOnPaste =
      getBooleanKey("enable-indent-on-paste").getOrElse(true)
    val rangeFormattingProviders = getParsedArrayKey(
      "range-formatting-providers",
      values =>
        RangeFormattingProviders.fromConfigOrFeatureFlag(
          values,
          featureFlags,
        ),
    ).getOrElse(RangeFormattingProviders.default)
    val enableSemanticHighlighting =
      getBooleanKey("enable-semantic-highlighting").getOrElse(true)
    val excludedPackages =
      getStringListKey("excluded-packages")
    // `automatic` should be treated as None
    // It was added only to have a meaningful option value in vscode
    val defaultScalaVersion =
      getStringKey("fallback-scala-version").filter(_ != "automatic")
    val fallbackClasspath = getParsedArrayKey(
      "fallback-classpath",
      values =>
        FallbackClasspathConfig.fromConfigOrFeatureFlag(
          values,
          featureFlags,
        ),
    ).getOrElse(FallbackClasspathConfig.default)
    val fallbackSourcepath = getParsedKey(
      "fallback-sourcepath",
      value =>
        FallbackSourcepathConfig.fromConfigOrFeatureFlag(
          value,
          featureFlags,
        ),
    ).getOrElse(FallbackSourcepathConfig.default)
    val disableTestCodeLenses = {
      val isTestExplorerEnabled = clientConfiguration.isTestExplorerProvider()
      getStringKey("test-user-interface").map(_.toLowerCase()) match {
        case Some("test explorer") if isTestExplorerEnabled =>
          TestUserInterfaceKind.TestExplorer
        case _ =>
          TestUserInterfaceKind.CodeLenses
      }
    }
    val javaFormatConfig =
      getSubKey("java-format").flatMap(subKey =>
        getStringKeyOnObj("eclipse-config-path", subKey).map(f =>
          JavaFormatConfig(
            AbsolutePath(f),
            getStringKeyOnObj("eclipse-profile", subKey),
          )
        )
      )
    val javaFormatter = getParsedKey(
      "java-formatter",
      value =>
        JavaFormatterConfig.fromString(value.getOrElse("google-java-format")),
    )

    val scalafixRulesDependencies =
      getStringListKey("scalafix-rules-dependencies").getOrElse(Nil)

    val customProjectRoot = getStringKey("custom-project-root")
    val verboseCompilation =
      getBooleanKey("verbose-compilation").getOrElse(false)

    val autoImportBuilds =
      getStringKey("auto-import-builds").map(_.trim().toLowerCase()) match {
        case Some("initial") => AutoImportBuildKind.Initial
        case Some("all") => AutoImportBuildKind.All
        case _ => AutoImportBuildKind.Off
      }

    val scalaCliLauncher = getStringKey("scala-cli-launcher")
    val scalaCliEnabled = getBooleanKey("scala-cli-enabled").getOrElse(false)
    val defaultBspToBuildTool =
      getBooleanKey("default-bsp-to-build-tool").getOrElse(false)

    val presentationCompilerDiagnostics =
      getBooleanKey("presentation-compiler-diagnostics").getOrElse(true)
    val buildChangedAction = getParsedKey(
      "build-changed-action",
      value =>
        BuildChangedAction.fromString(
          value.getOrElse(BuildChangedAction.default.value)
        ),
    ).getOrElse(BuildChangedAction.default)
    val buildOnChange = getBooleanKey("build-on-change").getOrElse(true)
    val buildOnFocus = getBooleanKey("build-on-focus").getOrElse(true)
    val preferredBuildServer = getStringKey("preferred-build-server")
    val useSourcePath = getBooleanKey("use-source-path").getOrElse(true)
    val workspaceSymbolProvider = getParsedKey(
      "workspace-symbol-provider",
      value =>
        WorkspaceSymbolProviderConfig.fromConfigOrFeatureFlag(
          value,
          featureFlags,
        ),
    ).getOrElse(WorkspaceSymbolProviderConfig.default)
    val definitionIndexStrategy = getParsedKey(
      "definition-index-strategy",
      value =>
        DefinitionIndexStrategy.fromConfigOrFeatureFlag(
          value,
          featureFlags,
        ),
    ).getOrElse(DefinitionIndexStrategy.default)
    val definitionProviders = getParsedArrayKey(
      "definition-providers",
      values =>
        DefinitionProviderConfig.fromConfigOrFeatureFlag(
          values,
          featureFlags,
        ),
    ).getOrElse(DefinitionProviderConfig.default)
    val javaOutlineProvider = getParsedKey(
      "java-outline-provider",
      value =>
        JavaOutlineProviderConfig.fromConfigOrFeatureFlag(
          value,
          featureFlags,
        ),
    ).getOrElse(JavaOutlineProviderConfig.default)
    val protoOutlineProvider = getParsedKey(
      "proto-outline-provider",
      value =>
        ProtoOutlineProviderConfig.fromConfigOrFeatureFlag(
          value,
          featureFlags,
        ),
    ).getOrElse(ProtoOutlineProviderConfig.default)
    val javaSymbolLoader = getParsedKey(
      "java-symbol-loader",
      value =>
        JavaSymbolLoaderConfig.fromConfigOrFeatureFlag(
          value,
          featureFlags,
        ),
    ).getOrElse(JavaSymbolLoaderConfig.default)
    val javaTurbineRecompileDelay = TurbineRecompileDelayConfig.fromConfig(
      getStringKey("java-turbine-recompile-delay")
    )
    val javacServicesOverrides =
      getKey(
        "javac-services-overrides",
        json,
        { element =>
          JavacServicesOverrides.fromJson(element) match {
            case Right(ok) => Some(ok)
            case Left(error) =>
              errors += s"json error: $error"
              None
          }
        },
      ).getOrElse(JavacServicesOverrides.defaultFromFeatureFlags(featureFlags))
    val compilerProgress = getParsedKey(
      "compiler-progress",
      value =>
        CompilerProgressConfig.fromConfigOrFeatureFlag(
          value,
          featureFlags,
        ),
    ).getOrElse(CompilerProgressConfig.default)
    val referenceProvider = getParsedKey(
      "reference-provider",
      value =>
        ReferenceProviderConfig.fromConfigOrFeatureFlag(
          value,
          featureFlags,
        ),
    ).getOrElse(ReferenceProviderConfig.default)
    val additionalPcChecks = getParsedArrayKey(
      "additional-pc-checks",
      values =>
        AdditionalPcChecksConfig.fromConfigOrFeatureFlag(
          values,
          featureFlags,
        ),
    ).getOrElse(AdditionalPcChecksConfig.default)
    val scalaImportsPlacement = getParsedKey(
      "scala-imports-placement",
      value =>
        ScalaImportsPlacementConfig.fromConfigOrFeatureFlag(
          value,
          featureFlags,
        ),
    ).getOrElse(ScalaImportsPlacementConfig.default)
    val batchSemanticdbCompilerInstances =
      BatchSemanticdbConfig.fromConfigOrFeatureFlag(
        getIntKey("batch-semanticdb-compiler-instances"),
        featureFlags,
      ) match {
        case Right(ok) => ok
        case Left(error) =>
          errors += error
          BatchSemanticdbConfig.default
      }
    val promptBuildImport =
      getBooleanKey("prompt-build-import").getOrElse(false)
    val protobufLspConfig =
      getKey(
        "protobuf-lsp",
        json,
        { element =>
          ProtobufLspConfig.fromJson(element) match {
            case Right(ok) => Some(ok)
            case Left(error) =>
              errors += s"json error: $error"
              None
          }
        },
      ).getOrElse(ProtobufLspConfig.defaultFromFeatureFlags(featureFlags))
    val enableBestEffort =
      getBooleanKey("enable-best-effort").getOrElse(false)

    val startMcpServer = getBooleanKey("start-mcp-server").getOrElse(false)

    val mcpClient = getStringKey("mcp-client")

    if (errors.isEmpty) {
      Right(
        UserConfiguration(
          javaHome,
          sbtScript,
          gradleScript,
          mavenScript,
          millScript,
          scalafmtConfigPath,
          scalafixConfigPath,
          symbolPrefixes,
          shimGlobs,
          worksheetScreenWidth,
          worksheetCancelTimeout,
          bloopSbtAlreadyInstalled,
          bloopVersion,
          bloopJvmProperties,
          superMethodLensesEnabled,
          gotoTestLensesEnabled,
          inlayHintsOptions,
          enableStripMarginOnTypeFormatting,
          enableIndentOnPaste,
          rangeFormattingProviders,
          enableSemanticHighlighting,
          excludedPackages,
          defaultScalaVersion,
          fallbackClasspath,
          fallbackSourcepath,
          disableTestCodeLenses,
          javaFormatConfig,
          javaFormatter,
          scalafixRulesDependencies,
          customProjectRoot,
          verboseCompilation,
          autoImportBuilds,
          scalaCliLauncher,
          scalaCliEnabled,
          defaultBspToBuildTool,
          presentationCompilerDiagnostics,
          buildChangedAction,
          buildOnChange,
          buildOnFocus,
          preferredBuildServer,
          useSourcePath,
          workspaceSymbolProvider,
          definitionProviders,
          definitionIndexStrategy,
          javaOutlineProvider,
          protoOutlineProvider,
          javaSymbolLoader,
          javaTurbineRecompileDelay,
          javacServicesOverrides,
          compilerProgress,
          referenceProvider,
          additionalPcChecks,
          scalaImportsPlacement,
          batchSemanticdbCompilerInstances,
          promptBuildImport,
          protobufLspConfig,
          enableBestEffort,
          defaultShell,
          startMcpServer,
          mcpClient,
        )
      )
    } else {
      Left(errors.toList)
    }
  }

  def parse(config: String): JsonObject = {
    import JsonParser._
    config.parseJson.getAsJsonObject
  }

}

sealed trait TestUserInterfaceKind
object TestUserInterfaceKind {
  object CodeLenses extends TestUserInterfaceKind {
    override def toString: String = "code lenses"
  }
  object TestExplorer extends TestUserInterfaceKind {
    override def toString: String = "test explorer"
  }
}
object BuildChangedAction {
  def default: BuildChangedAction = BuildChangedAction("none")
  def prompt: BuildChangedAction = BuildChangedAction("prompt")
  def fromString(value: String): Either[String, BuildChangedAction] =
    value match {
      case ok @ ("none" | "prompt") => Right(BuildChangedAction(ok))
      case _ =>
        Left(
          s"Invalid build changed action: $value. Expected 'none' or 'prompt'."
        )
    }

}
case class BuildChangedAction(value: String) {
  require(
    List("none", "prompt").contains(value),
    s"Invalid build changed action: $value",
  )
  def isNone: Boolean = value == "none"
  def isPrompt: Boolean = value == "prompt"
}

sealed trait BloopJvmProperties {
  def properties: Option[List[String]]
}
object BloopJvmProperties {
  case object Uninitialized extends BloopJvmProperties {
    def properties: Option[List[String]] = None
  }
  case object Empty extends BloopJvmProperties {
    def properties: Option[List[String]] = None
  }
  case class WithProperties(props: List[String]) extends BloopJvmProperties {
    def properties: Option[List[String]] = Some(props)
  }
}

sealed trait AutoImportBuildKind
object AutoImportBuildKind {
  case object Off extends AutoImportBuildKind
  case object Initial extends AutoImportBuildKind
  case object All extends AutoImportBuildKind
}
