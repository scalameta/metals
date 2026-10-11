package scala.meta.internal.metals.config

import scala.meta.infra.FeatureFlag
import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.ExcludedPackagesHandler
import scala.meta.internal.metals.InlayHintsOptions
import scala.meta.internal.metals.JavaFormatterConfig
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.config.CompilerProgressConfig
import scala.meta.internal.metals.config.DefinitionProviderConfig
import scala.meta.internal.metals.config.EclipseFormatConfig
import scala.meta.internal.metals.config.FallbackClasspathConfig
import scala.meta.internal.metals.config.FallbackSourcepathConfig
import scala.meta.internal.metals.config.JavaSymbolLoaderConfig
import scala.meta.internal.metals.config.ReferenceProviderConfig
import scala.meta.internal.metals.config.TurbineRecompileDelayConfig
import scala.meta.internal.metals.config.WorkspaceSymbolProviderConfig
import scala.meta.internal.metals.config.option._
import scala.meta.internal.mtags.Symbol
import scala.meta.pc.PresentationCompilerConfig

/**
 * Every user setting, in documentation order.
 *
 * `documented` is the public list (`metals config`, MCP, website). `settings`
 * is what [[UserConfiguration.fromJson]] reads and [[UserConfiguration.toString]]
 * writes. Both are views of [[settings]].
 */
object UserConfigurationOptions {

  private val defaultExclusion =
    ExcludedPackagesHandler.defaultExclusions
      .map(_.dropRight(1))
      .mkString("\n")
      .replace("/", ".")

  def settings: List[UserConfigurationOption[_]] = List(
    /*
     * {
     *   "javaHome": "/Library/Java/JavaVirtualMachines/jdk1.8.0_192.jdk/Contents/Home"
     * }
     */
    OptionalStringConfigurationOption(
      key = "java-home",
      example =
        "/Library/Java/JavaVirtualMachines/jdk1.8.0_192.jdk/Contents/Home",
      title = "Java Home directory",
      description =
        "The Java Home directory used for indexing JDK sources and locating the `java` binary.",
      defaultDescription = Some(
        "`JAVA_HOME` environment variable with fallback to `user.home` system property."
      ),
    )(
      config => config.javaHome,
      (config, value) => config.copy(javaHome = value),
    ),
    /*
     * {
     *   "sbtScript": "/usr/local/bin/sbt"
     * }
     */
    OptionalStringConfigurationOption(
      key = "sbt-script",
      example = """"/usr/local/bin/sbt"""",
      title = "sbt script",
      description =
        """|Optional absolute path to an `sbt` executable to use for running `sbt bloopInstall`.
           |By default, Metals uses `java -jar sbt-launch.jar` with an embedded launcher while respecting
           |`.jvmopts` and `.sbtopts`. Update this setting if your `sbt` script requires more customizations
           |like using environment variables.
           |""".stripMargin,
    )(
      config => config.sbtScript,
      (config, value) => config.copy(sbtScript = value),
    ),
    /*
     * {
     *   "gradleScript": "/usr/local/bin/gradle"
     * }
     */
    OptionalStringConfigurationOption(
      key = "gradle-script",
      example = "/usr/local/bin/gradle",
      title = "Gradle script",
      description =
        """Optional absolute path to a `gradle` executable to use for running `gradle bloopInstall`.
          |Update this setting if your `gradle` script requires more customizations
          |like using environment variables.
          |""".stripMargin,
    )(
      config => config.gradleScript,
      (config, value) => config.copy(gradleScript = value),
    ),
    /*
     * {
     *   "mavenScript": "/usr/local/bin/mvn"
     * }
     */
    OptionalStringConfigurationOption(
      key = "maven-script",
      example = """"/usr/local/bin/mvn"""",
      title = "Maven script",
      description =
        """Optional absolute path to a `maven` executable to use for generating bloop config.
          |Update this setting if your `maven` script requires more customizations
          |""".stripMargin,
    )(
      config => config.mavenScript,
      (config, value) => config.copy(mavenScript = value),
    ),
    /*
     * {
     *   "millScript": "/usr/local/bin/mill"
     * }
     */
    OptionalStringConfigurationOption(
      key = "mill-script",
      example = """"/usr/local/bin/mill"""",
      title = "Mill script",
      description =
        s"""Optional absolute path to a `mill` executable to use for running `mill mill.contrib.bloop.Bloop/install`.
           |By default, Metals uses mill wrapper script with ${BuildInfo.millVersion} mill version. Update this setting if your `mill` script requires more customizations
           |like using environment variables.
           |""".stripMargin,
    )(
      config => config.millScript,
      (config, value) => config.copy(millScript = value),
    ),
    /*
     * {
     *   "scalafmtConfigPath": "project/.scalafmt.conf"
     * }
     */
    OptionalPathConfigurationOption(
      key = "scalafmt-config-path",
      example = """"project/.scalafmt.conf"""",
      title = "Scalafmt config path",
      description =
        """Optional custom path to the .scalafmt.conf file.
          |It should be a path (relative or absolute - though a relative path is recommended) and use
          |forward slashes `/` for file separators (even on Windows).
          |""".stripMargin,
      default = ".scalafmt.conf",
    )(
      config => config.scalafmtConfigPath,
      (config, value) => config.copy(scalafmtConfigPath = value),
    ),
    /*
     * {
     *   "scalafixConfigPath": "project/.scalafix.conf"
     * }
     */
    OptionalPathConfigurationOption(
      key = "scalafix-config-path",
      example = """"project/.scalafix.conf"""",
      title = "Scalafix config path",
      description =
        """Optional custom path to the .scalafix.conf file.
          |It should be a path (relative or absolute - though an absolute path is recommended) and use
          |forward slashes `/` for file separators (even on Windows).
          |""".stripMargin,
      default = ".scalafix.conf",
    )(
      config => config.scalafixConfigPath,
      (config, value) => config.copy(scalafixConfigPath = value),
    ),
    /*
     * {
     *   "symbolPrefixes": {
     *     "scala/": "scala-standard."
     *   }
     * }
     */
    symbolPrefixes(
      key = "symbol-prefixes",
      title = "Symbol prefixes",
      description = "Prefixes replacements used when printing symbols. " +
        "You can replace package names with shorter ones to make them more readable.",
      example = """{ "scala/": "scala-standard." }""",
      default = "{}",
    ),
    /*
     * {
     *   "shimGlobs": {
     *     "default": ["shims.scala", "** /shims/ *.scala"]
     *   }
     * }
     */
    shimGlobs(
      key = "shim-globs",
      title = "Shim file globs",
      description =
        """|Named groups of file glob patterns used to detect shim files in the presentation compiler.
           |Entries follow the 'glob' syntax of FileSystem.getPathMatcher, e.g. use `**/shims.scala` to
           |match all shims.scala files in the workspace.
           |Values from all groups are combined into a single list.
           |""".stripMargin,
      example = """{ "default": ["shims.scala", "**/shims/*.scala"] }""",
      default = """`{}`.""",
    ),
    /*
     * {
     *   "scalafixRulesDependencies": ["com.github.liancheng::organize-imports:0.6.0"]
     * }
     */
    StringListConfigurationOption(
      key = "scalafix-rules-dependencies",
      example = s"""["com.github.liancheng::organize-imports:0.6.0"]""",
      title = "Scalafix rules dependencies",
      description =
        """Optional list of Scalafix rules dependencies to use for running `scalafix --rules`.""",
      default = "[]",
    )(
      config => config.scalafixRulesDependencies,
      (config, value) => config.copy(scalafixRulesDependencies = value),
    ),
    /*
     * {
     *   "scalafixLintEnabled": false
     * }
     */
    BooleanConfigurationOption(
      key = "scalafix-lint-enabled",
      defaultValue = false,
      example = "false",
      title = "Enable Scalafix lint diagnostics",
      description =
        """When enabled, Scalafix rules from `.scalafix.conf` will be run on
          |semanticdb updates and lint diagnostics will be published alongside
          |compiler diagnostics. Only lint diagnostics are shown; no code rewrites are applied.
          |""".stripMargin,
    )(
      config => config.scalafixLintEnabled,
      (config, value) => config.copy(scalafixLintEnabled = value),
    ),
    /*
     * {
     *   "excludedPackages": ["akka.actor.typed.javadsl"]
     * }
     */
    OptionalStringListConfigurationOption(
      key = "excluded-packages",
      example = """["akka.actor.typed.javadsl"]""",
      title = "Excluded Packages",
      description =
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
      default = "[]",
    )(
      config => config.excludedPackages,
      (config, value) => config.copy(excludedPackages = value),
    ),
    /*
     * {
     *   "bloopSbtAlreadyInstalled": false
     * }
     */
    BooleanConfigurationOption(
      key = "bloop-sbt-already-installed",
      defaultValue = false,
      example = "false",
      title = "Don't generate Bloop plugin file for sbt",
      description =
        """|If true, Metals will not generate `metals.sbt` files under the assumption that sbt-bloop 
           |is already manually installed in the sbt build. Build import will fail with a 'not valid command bloopInstall'
           |error in case Bloop is not manually installed in the build when using this option.""".stripMargin,
    )(
      config => config.bloopSbtAlreadyInstalled,
      (config, value) => config.copy(bloopSbtAlreadyInstalled = value),
    ),
    /*
     * {
     *   "bloopVersion": "1.4.0-RC1"
     * }
     */
    OptionalStringConfigurationOption(
      key = "bloop-version",
      example = """"2.1.1"""",
      title = "Version of Bloop",
      description =
        """|This version will be used for the Bloop build tool plugin, for any supported build tool,
           |while importing in Metals.""".stripMargin,
      default = BuildInfo.bloopVersion,
    )(
      config => config.bloopVersion,
      (config, value) => config.copy(bloopVersion = value),
    ),
    /*
     * {
     *   "bloopJvmProperties": ["-Xms1024m", "-Xmx4096m"]
     * }
     */
    CustomConfigurationOption[BloopJvmProperties](
      key = "bloop-jvm-properties",
      title = "Bloop JVM Properties",
      description =
        """|Optional list of JVM properties to pass along to the Bloop server.
           |Please follow this guide for the format https://scalacenter.github.io/bloop/docs/server-reference#global-settings-for-the-server"
           |""".stripMargin,
      example = """["-Xmx1G"]""",
      default = """["-Xmx1G"]""",
      isArray = true,
    )(
      _.bloopJvmProperties,
      (config, value) => config.copy(bloopJvmProperties = value),
    )(
      readValue = context =>
        context.getStringList("bloop-jvm-properties") match {
          case None => BloopJvmProperties.Empty
          case Some(props) => BloopJvmProperties.WithProperties(props)
        },
      writeValue = properties => properties.properties.map(_.asJava),
    ),
    /*
     * {
     *   "superMethodLensesEnabled": false
     * }
     */
    BooleanConfigurationOption(
      key = "super-method-lenses-enabled",
      defaultValue = false,
      example = "false",
      title = "Should display lenses with links to super methods",
      description =
        """|Super method lenses are visible above methods definition that override another methods. Clicking on a lens jumps to super method definition.
           |Disabled lenses are not calculated for opened documents which might speed up document processing.
           |
           |""".stripMargin,
    )(
      config => config.superMethodLensesEnabled,
      (config, value) => config.copy(superMethodLensesEnabled = value),
    ),
    /*
     * {
     *   "gotoTestLensesEnabled": true
     * }
     */
    BooleanConfigurationOption(
      "goto-test-lenses-enabled",
      defaultValue = false,
      example = "true",
      title = "Enable goto-test lenses",
      description = "Show code lenses that jump to tests.",
    )(
      _.gotoTestLensesEnabled,
      (config, value) => config.copy(gotoTestLensesEnabled = value),
    ),
    /*
     * {
     *   "inlayHintsOptions": {
     *     "inferredTypes": { "enable": false },
     *     "namedParameters": { "enable": false },
     *     "byNameParameters": { "enable": false },
     *     "implicitArguments": { "enable": false },
     *     "implicitConversions": { "enable": false },
     *     "typeParameters": { "enable": false },
     *     "hintsInPatternMatch": { "enable": false },
     *     "hintsXRayMode": { "enable": false },
     *     "closingLabels": { "enable": false }
     *   }
     * }
     */
    ObjectConfigurationOption[InlayHintsOptions](
      key = "inlay-hints",
      title = "Inlay hints",
      description = "Inlay hint options.",
      example = """{ "inferredTypes": { "enable": false } }""",
      default = "{}",
      defaultValue = InlayHintsOptions.none,
      subFields = List(
        BooleanConfigurationOption.forConfig[InlayHintsOptions](
          key = "inferred-types.enable",
          title = "Inlay hints for inferred types",
          description =
            """|When this option is enabled, each method that can have inferred types has them
               |displayed either as additional decorations if they are supported by the editor or
               |shown in the hover.
               |""".stripMargin,
          defaultValue = false,
          example = "false",
        )(
          _.inferredType,
          (config, value) => config.copy(inferredType = value),
        ),
        BooleanConfigurationOption.forConfig[InlayHintsOptions](
          key = "named-parameters.enable",
          title = "Inlay hints for named parameters",
          description =
            """|When this option is enabled, each method has an added parameter name next to its arguments
               |displayed either as additional decorations if they are supported by the editor or 
               |shown in the hover.
               |""".stripMargin,
          defaultValue = false,
          example = "false",
        )(
          _.namedParameters,
          (config, value) => config.copy(namedParameters = value),
        ),
        BooleanConfigurationOption.forConfig[InlayHintsOptions](
          key = "by-name-parameters.enable",
          title = "Inlay hints for by-name parameters",
          description =
            """|When this option is enabled, each method that has by-name parameters has them 
               |displayed either as additional '=>' decorations if they are supported by the editor or 
               |shown in the hover.
               |""".stripMargin,
          defaultValue = false,
          example = "false",
        )(
          _.byNameParameters,
          (config, value) => config.copy(byNameParameters = value),
        ),
        BooleanConfigurationOption.forConfig[InlayHintsOptions](
          key = "implicit-arguments.enable",
          title = "Inlay hints for implicit arguments",
          description =
            """|When this option is enabled, each method that has implicit arguments has them
               |displayed either as additional decorations if they are supported by the editor or
               |shown in the hover.
               |""".stripMargin,
          defaultValue = false,
          example = "false",
        )(
          _.implicitArguments,
          (config, value) => config.copy(implicitArguments = value),
        ),
        BooleanConfigurationOption.forConfig[InlayHintsOptions](
          key = "implicit-conversions.enable",
          title = "Inlay hints for implicit conversions",
          description =
            """|When this option is enabled, each place where an implicit method or class is used has it
               |displayed either as additional decorations if they are supported by the editor or
               |shown in the hover.
               |""".stripMargin,
          defaultValue = false,
          example = "false",
        )(
          _.implicitConversions,
          (config, value) => config.copy(implicitConversions = value),
        ),
        BooleanConfigurationOption.forConfig[InlayHintsOptions](
          key = "type-parameters.enable",
          title = "Inlay hints for type parameters",
          description =
            """|When this option is enabled, each place when a type parameter is applied has it
               |displayed either as additional decorations if they are supported by the editor or
               |shown in the hover.
               |""".stripMargin,
          defaultValue = false,
          example = "false",
        )(
          _.typeParameters,
          (config, value) => config.copy(typeParameters = value),
        ),
        BooleanConfigurationOption.forConfig[InlayHintsOptions](
          key = "hints-in-pattern-match.enable",
          title = "Inlay hints for pattern matches",
          description =
            """|When this option is enabled, each place when a type is inferred in a pattern match has it
               |displayed either as additional decorations if they are supported by the editor or
               |shown in the hover.
               |""".stripMargin,
          defaultValue = false,
          example = "false",
        )(
          _.hintsInPatternMatch,
          (config, value) => config.copy(hintsInPatternMatch = value),
        ),
        BooleanConfigurationOption.forConfig[InlayHintsOptions](
          key = "hints-x-ray-mode.enable",
          title =
            "Inlay hints for intermediate types of multi-line expressions",
          description =
            """|When this option is enabled, each method/attribute call in a multi-line chain will get
               | its own type annotation.
               |""".stripMargin,
          defaultValue = false,
          example = "false",
        )(
          _.hintsXRayMode,
          (config, value) => config.copy(hintsXRayMode = value),
        ),
        BooleanConfigurationOption.forConfig[InlayHintsOptions](
          key = "closing-labels.enable",
          title = "Inlay hints for closing labels",
          description =
            """|When this option is enabled, each method/class/object definition that uses braces syntax,
               | will get a closing label hint next to the closing brace with the name of the definition.
               |""".stripMargin,
          defaultValue = false,
          example = "false",
        )(
          _.closingLabels,
          (config, value) => config.copy(closingLabels = value),
        ),
      ),
    )(
      config => config.inlayHints,
      (config, value) => config.copy(inlayHints = value),
    ),
    /*
     * {
     *   "enableStripMarginOnTypeFormatting": true
     * }
     */
    BooleanConfigurationOption(
      key = "enable-strip-margin-on-type-formatting",
      defaultValue = true,
      example = "true",
      title = "Enable strip margin on type formatting",
      description =
        "When enabled, Metals inserts a strip margin on multiline strings while typing.",
    )(
      config => config.enableStripMarginOnTypeFormatting,
      (config, value) => config.copy(enableStripMarginOnTypeFormatting = value),
    ),
    /*
     * {
     *   "enableIndentOnPaste": false
     * }
     */
    BooleanConfigurationOption(
      key = "enable-indent-on-paste",
      defaultValue = false,
      example = "false",
      title = "Indent snippets when pasted.",
      description =
        """|When this option is enabled, when a snippet is pasted into a Scala file, Metals will
           |try to adjust the indentation to that of the current cursor.
           |""".stripMargin,
    )(
      config => config.enableIndentOnPaste,
      (config, value) => config.copy(enableIndentOnPaste = value),
    ),
    /*
     * {
     *   "rangeFormattingProviders": ["scalafmt"]
     * }
     */
    StringListConfigurationOption(
      key = "range-formatting-providers",
      title = "Range formatting providers",
      description =
        """Providers used for range formatting. Valid values are "scalafmt".""",
      example = """["scalafmt"]""",
      default = """["scalafmt"]""",
      validValues = List("scalafmt"),
      defaultValue = List("scalafmt"),
      fromFeatureFlag = featureFlags => {
        if (
          featureFlags.readBooleanOrFalse(FeatureFlag.SCALAFMT_RANGE_FORMATTER)
        ) {
          List("scalafmt")
        } else {
          Nil
        }
      },
    )(
      config => config.rangeFormattingProviders,
      (config, value) => config.copy(rangeFormattingProviders = value),
    ),
    /* {
     *   "fallbackScalaVersion": "3.3.0"
     * }
     */
    CustomConfigurationOption[Option[String]](
      key = "fallback-scala-version",
      title = "Default fallback Scala version",
      description =
        """|The Scala compiler version that is used as the default or fallback in case a file
           |doesn't belong to any build target or the specified Scala version isn't supported by Metals.
           |This applies to standalone Scala files, worksheets and Scala CLI scripts.
        """.stripMargin,
      example = BuildInfo.scala3,
      default = BuildInfo.scala3,
    )(
      config => config.fallbackScalaVersion,
      (config, value) => config.copy(fallbackScalaVersion = value),
    )(
      readValue = context =>
        context.getString("fallback-scala-version").filter(_ != "automatic"),
      writeValue = value => value,
    ),
    /*
     * {
     *   "worksheetCancelTimeout": 4
     * }
     */
    IntConfigurationOption(
      key = "worksheet-cancel-timeout",
      defaultValue = 4,
      example = "10",
      title = "Worksheet cancel timeout",
      description = "Seconds to wait before offering to cancel a worksheet.",
    )(
      config => config.worksheetCancelTimeout,
      (config, value) => config.copy(worksheetCancelTimeout = value),
    ),
    /*
     * {
     *   "testUserInterface": "code lenses"
     * }
     */
    ChoiceConfigurationOption[TestUserInterfaceKind](
      key = "test-user-interface",
      defaultValue = TestUserInterfaceKind.CodeLenses,
      choices = List(
        "code lenses" -> TestUserInterfaceKind.CodeLenses,
        "test explorer" -> TestUserInterfaceKind.TestExplorer,
      ),
      title = "Test UI used for tests and test suites",
      description =
        """|Default way of handling tests and test suites.  The only valid values are
           |"code lenses" and "test explorer".  See https://scalameta.org/metals/docs/integrations/test-explorer
           |for information on how to work with the test explorer.
           |""".stripMargin,
      example = "test explorer",
    )(
      config => config.testUserInterface,
      (config, value) => config.copy(testUserInterface = value),
    ),
    /*
     * {
     *   "javaFormat": {
     *     "configPath": "formatters/eclipse-formatter.xml",
     *     "profile": "GoogleStyle"
     *   }
     * }
     */
    ObjectConfigurationOption[EclipseFormatConfig](
      key = "eclipse-format",
      title = "Eclipse java format config",
      description = "Eclipse Java formatter configuration path and profile.",
      example = """{ "configPath": "formatters/eclipse-formatter.xml" }""",
      default = "{ profile: \"GoogleStyle\" }",
      defaultValue = EclipseFormatConfig.default,
      oldNames = List("java-format"),
      subFields = List(
        OptionalPathConfigurationOption.forConfig[EclipseFormatConfig](
          key = "config-path",
          example = """"formatters/eclipse-formatter.xml"""",
          title = "Eclipse Java formatter config path",
          description =
            """Optional custom path to the eclipse-formatter.xml file.
              |It should be a path (relative or absolute - though an absolute path is recommended) and use
              |forward slashes `/` for file separators (even on Windows).
              |""".stripMargin,
          defaultValue = None,
          oldNames = List("eclipse-config-path"),
        )(
          _.eclipseFormatConfigPath,
          (config, value) => config.copy(eclipseFormatConfigPath = value),
        ),
        OptionalStringConfigurationOption.forConfig[EclipseFormatConfig](
          key = "profile",
          example = """"GoogleStyle"""",
          title = "Eclipse Java formatting profile",
          description =
            """|If the Eclipse formatter file contains more than one profile, this option can be used to control which is used.
               |""".stripMargin,
          defaultValue = Some("GoogleStyle"),
          oldNames = List("eclipse-profile"),
        )(
          format => format.eclipseFormatProfile,
          (config, value) => config.copy(eclipseFormatProfile = value),
        ),
      ),
    )(
      config => config.eclipseFormat,
      (config, value) => config.copy(eclipseFormat = value),
    ),
    /*
     * {
     *   "javaFormatter": "googleJavaFormat"
     * }
     */
    ChoiceConfigurationOption[JavaFormatterConfig](
      key = "java-formatter",
      title = "Java formatter",
      description =
        """|The Java formatter to use. Valid values are "eclipse", "googleJavaFormat", or "none".
           |If "none" is specified, Java formatting will be disabled. If not specified, defaults to "googleJavaFormat".
           |""".stripMargin,
      example = """"googleJavaFormat"""",
      defaultValue = JavaFormatterConfig.GoogleJavaFormat,
      choices = List(
        "eclipse" -> JavaFormatterConfig.Eclipse,
        "googleJavaFormat" -> JavaFormatterConfig.GoogleJavaFormat,
        "none" -> JavaFormatterConfig.None,
      ),
    )(
      config => config.javaFormatter,
      (config, value) => config.copy(javaFormatter = value),
    ),
    /*
     * {
     *   "scalaCliLauncher": "/usr/local/bin/scala-cli"
     * }
     */
    OptionalStringConfigurationOption(
      key = "scala-cli-launcher",
      example = """"/usr/local/bin/scala-cli"""",
      title = "Scala CLI launcher",
      description =
        """Optional absolute path to a `scala-cli` executable to use for running a Scala CLI BSP server.
          |By default, Metals uses the scala-cli from the PATH, or it's not found, downloads and runs Scala
          |CLI on the JVM (slower than native Scala CLI). Update this if you want to use a custom Scala CLI
          |launcher, not available in PATH.
          |""".stripMargin,
    )(
      config => config.scalaCliLauncher,
      (config, value) => config.copy(scalaCliLauncher = value),
    ),
    /*
     * {
     *   "scalaCliEnabled": true
     * }
     */
    BooleanConfigurationOption(
      key = "scala-cli-enabled",
      defaultValue = false,
      example = "true",
      title = "Enable Scala CLI",
      description =
        "When enabled, Metals starts a Scala CLI server for standalone files.",
    )(
      config => config.scalaCliEnabled,
      (config, value) => config.copy(scalaCliEnabled = value),
    ),
    /*
     * {
     *   "customProjectRoot": "backend/scalaProject/"
     * }
     */
    OptionalStringConfigurationOption(
      key = "custom-project-root",
      example = """"backend/scalaProject/"""",
      title = "Custom project root",
      description =
        """Optional relative path to your project's root.
          |If you want your project root to be the workspace/workspace root set it to "." .""".stripMargin,
    )(
      config => config.customProjectRoot,
      (config, value) => config.copy(customProjectRoot = value),
    ),
    /*
     * {
     *   "verboseCompilation": true
     * }
     */
    BooleanConfigurationOption(
      key = "verbose-compilation",
      defaultValue = false,
      example = "true",
      title = "Show all compilation debugging information",
      description =
        """|If a build server supports it (for example Bloop or Scala CLI), setting it to true
           |will make the logs contain all the possible debugging information including
           |about incremental compilation in Zinc.""".stripMargin,
    )(
      config => config.verboseCompilation,
      (config, value) => config.copy(verboseCompilation = value),
    ),
    /*
     * {
     *   "automaticImportBuild": "all"
     * }
     */
    ChoiceConfigurationOption[AutoImportBuildKind](
      key = "auto-import-builds",
      defaultValue = AutoImportBuildKind.Off,
      choices = List[(String, AutoImportBuildKind)](
        "off" -> AutoImportBuildKind.Off,
        "initial" -> AutoImportBuildKind.Initial,
        "all" -> AutoImportBuildKind.All,
      ),
      example = "all",
      title = "Import build when changes detected without prompting",
      description =
        """|Automatically import builds rather than prompting the user to choose. "initial" will
           |only automatically import a build when a project is first opened, "all" will automate
           |build imports after subsequent changes as well.""".stripMargin,
    )(
      config => config.autoImportBuilds,
      (config, value) => config.copy(autoImportBuilds = value),
    ),
    /*
     * {
     *   "targetBuildTool": "sbt"
     * }
     */
    ChoiceConfigurationOption[TargetBuildTool](
      key = "target-build-tool",
      title = "Preferred build tool when multiple are detected",
      description =
        """|The preferred build tool to use when multiple build definitions are detected in the workspace.
           |This prevents the build tool selection dialog from appearing on startup.
           |Valid values are: "sbt", "gradle", "mvn", "mill", "scala-cli", "bazel".
           |""".stripMargin,
      example = """"bazel"""",
      defaultValue = TargetBuildTool.None,
      choices = List(
        "sbt" -> TargetBuildTool.Sbt,
        "gradle" -> TargetBuildTool.Gradle,
        "mvn" -> TargetBuildTool.Maven,
        "mill" -> TargetBuildTool.Mill,
        "scala-cli" -> TargetBuildTool.ScalaCli,
        "bazel" -> TargetBuildTool.Bazel,
        "deder" -> TargetBuildTool.Deder,
        "none" -> TargetBuildTool.None,
      ),
    )(
      _.targetBuildTool,
      (config, value) => config.copy(targetBuildTool = value),
    ),
    /*
     * {
     *   "defaultBspToBuildTool": true
     * }
     */
    BooleanConfigurationOption(
      "default-bsp-to-build-tool",
      defaultValue = false,
      example = "true",
      title = "Default to using build tool as your build server.",
      description = """|If your build tool can also serve as a build server,
                       |default to using it instead of Bloop.
                       |""".stripMargin,
    )(
      config => config.defaultBspToBuildTool,
      (config, value) => config.copy(defaultBspToBuildTool = value),
    ),
    /*
     * {
     *   "presentationCompilerDiagnostics": true
     * }
     */
    BooleanConfigurationOption(
      key = "presentation-compiler-diagnostics",
      defaultValue = true,
      example = "false",
      title =
        "[Experimental] Show diagnostics messages from the Scala presentation compiler",
      description =
        """|Show presentation compiler errors and warnings as you type. This gives a
           |much faster feedback loop but may show incorrect or incomplete error messages. Only
           |supported in Scala 2.
           |""".stripMargin,
    )(
      _.presentationCompilerDiagnostics,
      (config, value) => config.copy(presentationCompilerDiagnostics = value),
    ),
    /*
     * {
     *   "buildChangedAction": "build"
     * }
     */
    ChoiceConfigurationOption[BuildChangedAction](
      key = "build-changed-action",
      title = "Build changed action",
      description =
        """What to do when build files change. Valid values are "none" and "prompt".""",
      example = "\"prompt\"",
      defaultValue = BuildChangedAction.None,
      choices = List(
        "none" -> BuildChangedAction.None,
        "prompt" -> BuildChangedAction.Prompt,
      ),
    )(
      config => config.buildChangedAction,
      (config, value) => config.copy(buildChangedAction = value),
    ),
    /*
     * {
     *   "buildOnChange": true
     * }
     */
    BooleanConfigurationOption(
      key = "build-on-change",
      defaultValue = true,
      example = "false",
      title = "Disable build-on-change",
      description =
        """|If enabled, Metals will automatically build the project when a file changes.
           |""".stripMargin,
    )(
      config => config.buildOnChange,
      (config, value) => config.copy(buildOnChange = value),
    ),
    /*
     * {
     *   "buildOnFocus": true
     * }
     */
    BooleanConfigurationOption(
      key = "build-on-focus",
      defaultValue = true,
      example = "false",
      title = "Enable or disable build-on-focus",
      description =
        """|If enabled, Metals will automatically build the project when a file is focused (opened).
           |""".stripMargin,
    )(
      config => config.buildOnFocus,
      (config, value) => config.copy(buildOnFocus = value),
    ),
    /*
     * {
     *   "preferredBuildServer": "bazelbsp"
     * }
     */
    OptionalStringConfigurationOption(
      key = "preferred-build-server",
      example = """"bazelbsp"""",
      title = "Preferred build server",
      description =
        """|If set, metals will prefer the specified build server when available instead
           |of prompting the user.
           |""".stripMargin,
      default = """empty string `""`.""",
    )(
      config => config.preferredBuildServer,
      (config, value) => config.copy(preferredBuildServer = value),
    ),
    /*
     * {
     *   "workspaceSymbolProvider": "mbt"
     * }
     */
    ChoiceConfigurationOption[WorkspaceSymbolProviderConfig](
      key = "workspace-symbol-provider",
      title = "Workspace Symbol Provider",
      description =
        """|The workspace symbol provider to use. The only valid values are "bsp" and "mbt".
           |- "bsp": The classic solution that only indexes sources from the BSP server.
           |- "mbt": A new BSP-free solution that indexes sources from the git repository.
           |""".stripMargin,
      example = "\"mbt\"",
      defaultValue = WorkspaceSymbolProviderConfig.MBT,
      choices = List(
        "bsp" -> WorkspaceSymbolProviderConfig.BSP,
        "mbt" -> WorkspaceSymbolProviderConfig.MBT,
      ),
      fromFeatureFlag = featureFlags => {
        val isMbtEnabled = featureFlags
          .readBooleanOrFalse(FeatureFlag.MBT_WORKSPACE_SYMBOL_PROVIDER) ||
          featureFlags.readBooleanOrFalse(FeatureFlag.MBT_V2_SYMBOL_INDEX)
        if (isMbtEnabled) {
          Some(WorkspaceSymbolProviderConfig.MBT)
        } else {
          None
        }
      },
    )(
      config => config.workspaceSymbolProvider,
      (config, value) => config.copy(workspaceSymbolProvider = value),
    ),
    /*
     * {
     *   "definitionProviders": ["mbt"]
     * }
     */
    ChoiceConfigurationOption[DefinitionProviderConfig](
      key = "definition-providers",
      title = "Definition providers",
      description =
        """Definition providers. Valid values are "mbt" and "protobuf", by default all are enabled.""",
      example = """["mbt"]""",
      defaultValue = DefinitionProviderConfig.All,
      choices = List(
        "mbt" -> DefinitionProviderConfig.MBT,
        "protobuf" -> DefinitionProviderConfig.Protobuf,
        "all" -> DefinitionProviderConfig.All,
      ),
      fromFeatureFlag = featureFlags => {
        val isMbtEnabled = featureFlags
          .readBooleanOrFalse(FeatureFlag.MBT_DEFINITION_PROVIDER)
        val isProtobufEnabled = featureFlags
          .readBooleanOrFalse(FeatureFlag.PROTOBUF_DEFINITION_PROVIDER)
        if (isMbtEnabled && isProtobufEnabled) {
          Some(DefinitionProviderConfig.All)
        } else if (isMbtEnabled) {
          Some(DefinitionProviderConfig.MBT)
        } else if (isProtobufEnabled) {
          Some(DefinitionProviderConfig.Protobuf)
        } else {
          None
        }
      },
    )(
      _.definitionProviders,
      (config, value) => config.copy(definitionProviders = value),
    ),
    /*
     * {
     *   "javaSymbolLoader": "turbine-classpath"
     * }
     */
    ChoiceConfigurationOption[JavaSymbolLoaderConfig](
      key = "java-symbol-loader",
      title = "Java symbol loader",
      description =
        """Java symbol loader to use when loading Java symbols in the Java presentation compiler:
        - "turbine-classpath": Loads Java symbols from the Turbine classpath.
        - "javac-sourcepath": Loads Java symbols from the Java source path.""".stripMargin,
      example = "\"javac-sourcepath\"",
      defaultValue = JavaSymbolLoaderConfig.TurbineClasspath,
      choices = List(
        "turbine-classpath" -> JavaSymbolLoaderConfig.TurbineClasspath,
        "javac-sourcepath" -> JavaSymbolLoaderConfig.JavacSourcepath,
      ),
      fromFeatureFlag = featureFlags => {
        val isTurbineClasspathEnabled = featureFlags
          .readBooleanOrFalse(FeatureFlag.JAVA_TURBINE_SYMBOL_LOADER)
        if (isTurbineClasspathEnabled) {
          Some(JavaSymbolLoaderConfig.TurbineClasspath)
        } else {
          None
        }
      },
    )(
      config => config.javaSymbolLoader,
      (config, value) => config.copy(javaSymbolLoader = value),
    ),
    /*
     * {
     *   "javaTurbineRecompileDelay": "1 minute"
     * }
     */
    CustomConfigurationOption[TurbineRecompileDelayConfig](
      key = "java-turbine-recompile-delay",
      title = "Java turbine recompile delay",
      description = "Delay before Turbine recompiles changed Java sources.",
      example = "\"1 minute\"",
      default = "",
    )(
      _.javaTurbineRecompileDelay,
      (config, value) => config.copy(javaTurbineRecompileDelay = value),
    )(
      context =>
        TurbineRecompileDelayConfig.fromConfig(
          context.getString("java-turbine-recompile-delay")
        ),
      config => Some(config.duration.toString()),
    ),
    /*
     * {
     *   "javaTurbineCache": true
     * }
     */
    BooleanConfigurationOption(
      key = "java-turbine-cache",
      defaultValue = false,
      example = "true",
      title = "Java turbine cache",
      description = "Persist Turbine compilation results between restarts.",
    )(
      _.javaTurbineCache,
      (config, value) => config.copy(javaTurbineCache = value),
    ),
    /*
     * {
     *   "javacServicesOverrides": {
     *     "names": true,
     *     "attr": true,
     *     "typeEnter": true,
     *     "enter": true,
     *   }
     * }
     */
    ObjectConfigurationOption[JavacServicesOverrides](
      key = "javac-services-overrides",
      title = "Javac services overrides",
      description = "Overrides for the Java presentation compiler services.",
      example = """{ "names": true }""",
      default =
        """{ "names": true, "attr": true, "typeEnter": true, "enter": true }""",
      defaultValue = JavacServicesOverrides.default,
      subFields = List(
        BooleanConfigurationOption.forConfig[JavacServicesOverrides](
          key = "names",
          title = "Override names",
          description =
            "Whether to override the names of the Java presentation compiler services.",
          example = "true",
          defaultValue = true,
          fromFeatureFlags = featureFlags =>
            featureFlags
              .readBoolean(FeatureFlag.JAVAC_OVERRIDE_NAMES)
              .map(_.booleanValue())
              .asScala,
        )(
          _.names,
          (config, value) => config.copy(names = value),
        ),
        BooleanConfigurationOption.forConfig[JavacServicesOverrides](
          key = "attr",
          title = "Override attr",
          description =
            "Whether to override the attr of the Java presentation compiler services.",
          example = "true",
          defaultValue = true,
          fromFeatureFlags = featureFlags =>
            featureFlags
              .readBoolean(FeatureFlag.JAVAC_OVERRIDE_ATTR)
              .map(_.booleanValue())
              .asScala,
        )(
          _.attr,
          (config, value) => config.copy(attr = value),
        ),
        BooleanConfigurationOption.forConfig[JavacServicesOverrides](
          key = "type-enter",
          title = "Override type enter",
          description =
            "Whether to override the type enter of the Java presentation compiler services.",
          example = "true",
          defaultValue = true,
          fromFeatureFlags = featureFlags =>
            featureFlags
              .readBoolean(FeatureFlag.JAVAC_OVERRIDE_TYPE_ENTER)
              .map(_.booleanValue())
              .asScala,
        )(
          _.typeEnter,
          (config, value) => config.copy(typeEnter = value),
        ),
        BooleanConfigurationOption.forConfig[JavacServicesOverrides](
          key = "enter",
          title = "Override enter",
          description =
            "Whether to override the enter of the Java presentation compiler services.",
          example = "true",
          defaultValue = true,
          fromFeatureFlags = featureFlags =>
            featureFlags
              .readBoolean(FeatureFlag.JAVAC_OVERRIDE_ENTER)
              .map(_.booleanValue())
              .asScala,
        )(
          _.enter,
          (config, value) => config.copy(enter = value),
        ),
      ),
    )(
      config => config.javacServicesOverrides,
      (config, value) => config.copy(javacServicesOverrides = value),
    ),
    /*
     * {
     *   "compilerProgress": "enabled"
     * }
     */
    ChoiceConfigurationOption[CompilerProgressConfig](
      key = "compiler-progress",
      title = "Compiler progress",
      description =
        """Compiler progress bars. Valid values are "enabled" and "disabled".""",
      example = "\"disabled\"",
      choices = List(
        "enabled" -> CompilerProgressConfig.Enabled,
        "disabled" -> CompilerProgressConfig.Disabled,
      ),
      defaultValue = CompilerProgressConfig.Enabled,
      fromFeatureFlag = featureFlags => {
        val isEnabled = featureFlags
          .readBooleanOrFalse(FeatureFlag.COMPILE_PROGRESS)
        if (isEnabled) {
          Some(CompilerProgressConfig.Enabled)
        } else {
          None
        }
      },
    )(
      config => config.compilerProgress,
      (config, value) => config.copy(compilerProgress = value),
    ),
    /*
     * {
     *   "referenceProvider": "bsp"
     * }
     */
    ChoiceConfigurationOption[ReferenceProviderConfig](
      key = "reference-provider",
      title = "Reference provider",
      description =
        """|Reference provider to use when providing references to symbols in the presentation compiler:
           |- "bsp": The classic solution that only indexes sources based on semanticdb documents.
           |- "mbt": A new BSP-free solution that indexes sources from the git repository when semanticdb documents are not available.""".stripMargin,
      example = "\"bsp\"",
      choices = List(
        "bsp" -> ReferenceProviderConfig.BSP,
        "mbt" -> ReferenceProviderConfig.MBT,
      ),
      defaultValue = ReferenceProviderConfig.MBT,
      fromFeatureFlag = featureFlags => {
        val isMbtEnabled = featureFlags
          .readBooleanOrFalse(FeatureFlag.MBT_REFERENCE_PROVIDER)
        if (isMbtEnabled) {
          Some(ReferenceProviderConfig.MBT)
        } else {
          None
        }
      },
    )(
      _.referenceProvider,
      (config, value) => config.copy(referenceProvider = value),
    ),
    /*
     * {
     *   "additionalPcChecks": ["refchecks"]
     * }
     */
    StringListConfigurationOption(
      key = "additional-pc-checks",
      title = "Additional presentation compiler checks to run",
      description =
        """|A list of additional compiler phases to run in the presentation compiler.
           |Valid values are "refchecks". When "refchecks" is included, the
           |presentation compiler will run the RefChecks phase for additional
           |type checking diagnostics.
           |""".stripMargin,
      example = """["refchecks"]""",
      default = "`[]`",
      fromFeatureFlag = featureFlags => {
        val isRefchecksEnabled = featureFlags
          .readBooleanOrFalse(FeatureFlag.RUN_PC_REFCHECKS)
        if (isRefchecksEnabled) {
          List("refchecks")
        } else {
          Nil
        }
      },
      validValues = List("refchecks"),
    )(
      config => config.additionalPcChecks,
      (config, value) => config.copy(additionalPcChecks = value),
    ),
    /*
     * {
     *   "scalaImportsPlacement": "append-last"
     * }
     */
    ChoiceConfigurationOption[PresentationCompilerConfig.ScalaImportsPlacement](
      key = "scala-imports-placement",
      title = "Scala imports placement",
      description =
        """|Where to place new Scala imports. Valid values are "append-last" and "smart".
           |- "append-last": Append imports at the end of the import block.
           |- "smart": Place imports intelligently based on prefix matching and alphabetical order.
           |""".stripMargin,
      example = "\"append-last\"",
      defaultValue = PresentationCompilerConfig.ScalaImportsPlacement.SMART,
      choices = List(
        "append-last" -> PresentationCompilerConfig.ScalaImportsPlacement.APPEND_LAST,
        "smart" -> PresentationCompilerConfig.ScalaImportsPlacement.SMART,
      ),
      fromFeatureFlag = featureFlags => {
        val isSmartEnabled = featureFlags
          .readBooleanOrFalse(FeatureFlag.SMART_SCALA_IMPORT_PLACEMENT)
        if (isSmartEnabled) {
          Some(PresentationCompilerConfig.ScalaImportsPlacement.SMART)
        } else {
          None
        }
      },
    )(
      _.scalaImportsPlacement,
      (config, value) => config.copy(scalaImportsPlacement = value),
    ),
    /*
     * {
     *   "batchSemanticdbCompilerInstances": 4
     * }
     */
    IntConfigurationOption(
      key = "batch-semanticdb-compiler-instances",
      title = "Batch semanticdb compiler instances",
      description =
        """|Number of compiler instances used when generating semanticdb in batch.
           |This is useful to speed up the semanticdb generation process when there are many files to process.
           |""".stripMargin,
      example = "4",
      defaultValue = 1,
      fromFeatureFlag = featureFlags =>
        featureFlags
          .readInt(FeatureFlag.BATCH_SEMANTICDB_COMPILER_INSTANCES, 1)
          .asScala
          .filter(_ > 0)
          .map(_.toInt),
    )(
      _.batchSemanticdbCompilerInstances,
      (config, value) => config.copy(batchSemanticdbCompilerInstances = value),
    ),
    /*
     * {
     *   "promptBuildImport": true
     * }
     */
    BooleanConfigurationOption(
      key = "prompt-build-import",
      defaultValue = false,
      example = "true",
      title = "Prompt Build Import",
      description =
        """|If enabled, Metals will prompt you to import or connect to a build server
           |when a new workspace is detected. When disabled, you can still manually
           |trigger import via the "Import build" command.
           |""".stripMargin,
    )(
      config => config.promptBuildImport,
      (config, value) => config.copy(promptBuildImport = value),
    ),
    /*
     * {
     *   "protobufLspEnabled": true
     * }
     */
    BooleanConfigurationOption(
      key = "protobuf-lsp-enabled",
      title = "Enabled Protobuf LSP",
      description = "If enabled, Metals will act as a Protobuf LSP server.",
      example = "false",
      defaultValue = true,
      fromFeatureFlags = featureFlags => {
        val isEnabled = featureFlags
          .readBooleanOrFalse(FeatureFlag.PROTOBUF_LSP)
        if (isEnabled) {
          Some(true)
        } else {
          None
        }
      },
      oldNames = List("protobuf-lsp"),
    )(
      config => config.protobufLspEnabled,
      (config, value) => config.copy(protobufLspEnabled = value),
    ),
    /*
     * {
     *   "enableBestEffort": true
     * }
     */
    BooleanConfigurationOption(
      key = "enable-best-effort",
      defaultValue = false,
      example = "true",
      title = "Use best effort compilation for Scala 3.",
      description =
        """|When using Scala 3, use best effort compilation to improve Metals 
           |correctness when the workspace doesn't compile. Can be used instead
           |of sourcepath based compilation.
           |""".stripMargin,
    )(
      config => config.enableBestEffort,
      (config, value) => config.copy(enableBestEffort = value),
    ),
    /*
     * {
     *   "defaultShell": "/usr/bin/fish"
     * }
     */
    OptionalStringConfigurationOption(
      key = "default-shell",
      example = "/usr/bin/fish",
      title = "Full path to the shell executable to be used as the default",
      description =
        """|Optionally provide a default shell executable to use for build operations.
           |This allows customizing the shell environment before build execution.
           |When specified, must use absolute path to the shell.
           |The configured shell will be used for all build-related subprocesses.
           |""".stripMargin,
    )(_.defaultShell, (config, value) => config.copy(defaultShell = value)),
    /*
     * {
     *   "startMcpServer": true
     * }
     */
    BooleanConfigurationOption(
      key = "start-mcp-server",
      defaultValue = false,
      example = "true",
      title = "Start MCP server",
      description =
        """|If Metals should start the MCP server, that an AI agent can connect to.
           |""".stripMargin,
    )(
      _.startMcpServer,
      (config, value) => config.copy(startMcpServer = value),
    ),
    /*
     * {
     *   "mcpClient": "claude"
     * }
     */
    OptionalStringConfigurationOption(
      key = "mcp-client",
      example = "claude",
      title = "MCP Client Name",
      description =
        """|This is used in situations where the client you're using doesn't match the editor
           |you're using or you also want an extra config generated, which is what Metals will
           |default to. For example if you use claude code cli in your terminal while you have
           |Metals running you would set this to "claude".
           |NOTE: This will generate an extra config if Metals supports the client you are passing in
           |and it will still generate the one matching your editor if it's also supported.
           |""".stripMargin,
    )(_.mcpClient, (config, value) => config.copy(mcpClient = value)),
    /*
     * {
     *   "mbtConfig": {
     *     "importGeneratedSources": true,
     *     "semanticdbCacheEnabled": true,
     *     "semanticdbCacheMaxSize": 2000
     *   }
     * }
     */
    ObjectConfigurationOption[MbtConfig](
      key = "mbt",
      title = "Configuration object for mbt related settings",
      description = "Configuration object for mbt related settings",
      example =
        """{ "importGeneratedSources": true, "semanticdbCacheEnabled": true, "semanticdbCacheMaxSize": 2000 }""",
      default = "{}",
      defaultValue = MbtConfig(),
      subFields = List(
        BooleanConfigurationOption.forConfig[MbtConfig](
          key = "import-generated-sources",
          defaultValue = false,
          example = """true""",
          title = "Import build tool generated sources.",
          description =
            """|If enabled, Metals will try to find the sources generated by the build tool
               |during MBT import and include them as unchecked sources.
               |""".stripMargin,
        )(
          _.importGeneratedSources,
          (config, value) => config.copy(importGeneratedSources = value),
        ),
        BooleanConfigurationOption.forConfig[MbtConfig](
          key = "semanticdb-cache-enabled",
          defaultValue = false,
          example = """true""",
          title = "Enable filesystem-based MBT Semanticdb cache",
          description =
            """|If enabled, Metals will persist semanticdb documents to disk in the
               |`.metals/semanticdb-cache` directory. This can improve performance for
               |find references and implementations operations in MBT mode by avoiding
               |recalculating semanticdb when files haven't changed.""".stripMargin,
        )(
          _.semanticdbCacheEnabled,
          (config, value) => config.copy(semanticdbCacheEnabled = value),
        ),
        IntConfigurationOption.forConfig[MbtConfig](
          key = "semanticdb-cache-max-size",
          defaultValue = Int.MaxValue,
          example = """2000""",
          title = "Semanticdb cache maximum size",
          description =
            """|Maximum number of semanticdb documents to keep in the in-memory cache.
               |When this limit is exceeded, the least recently used documents are evicted.
               |""".stripMargin,
        )(
          _.semanticdbCacheMaxSize,
          (config, value) => config.copy(semanticdbCacheMaxSize = value),
        ),
        IntConfigurationOption.forConfig[MbtConfig](
          key = "referencesTimeoutSeconds",
          defaultValue = 20,
          example = """20""",
          title =
            "Number of seconds to wait for references operations in MBT mode",
          description =
            """|Number of seconds to wait for references operations in MBT mode.
               |""".stripMargin,
        )(
          _.referencesTimeoutSeconds,
          (config, value) => config.copy(referencesTimeoutSeconds = value),
        ),
      ),
    )(
      config => config.mbt,
      (config, value) => config.copy(mbt = value),
    ),
    /*
     * {
     *   "fallbackClasspath": "all-3rdparty"
     * }
     */
    ChoiceConfigurationOption[FallbackClasspathConfig](
      key = "fallback-classpath",
      title = "Fallback classpath",
      description =
        """|Fallback classpath providers. Valid values include "none", "all-3rdparty", "guessed", "mbt", and "default".
           |Default includes both mbt and all-3rdparty. Guessed is experimental, tries to guess the classpath 
           |based on the workspace.""".stripMargin,
      example = """"all-3rdparty"""",
      defaultValue = FallbackClasspathConfig.Default,
      choices = List(
        "all3rdparty" -> FallbackClasspathConfig.All3rdparty,
        "guessed" -> FallbackClasspathConfig.Guessed,
        "mbt" -> FallbackClasspathConfig.Mbt,
        "default" -> FallbackClasspathConfig.Default,
        "none" -> FallbackClasspathConfig.None,
      ),
      fromFeatureFlag = featureFlags => {
        val isAll3rdpartyEnabled = featureFlags
          .readBooleanOrFalse(FeatureFlag.FALLBACK_CLASSPATH_ALL_3RD_PARTY)
        if (isAll3rdpartyEnabled) {
          Some(FallbackClasspathConfig.All3rdparty)
        } else {
          None
        }
      },
    )(
      _.fallbackClasspath,
      (config, value) => config.copy(fallbackClasspath = value),
    ),
    /*
     * {
     *   "fallbackSourcepath": "allSources"
     * }
     */
    ChoiceConfigurationOption[FallbackSourcepathConfig](
      key = "fallback-sourcepath",
      title = "Fallback sourcepath",
      description =
        """Fallback sourcepath to use for sources outside of existing build targets:
          |- "allSources": Use all sources in the workspace.
          |- "none": Do not use any sources outside of existing build targets.
          |""".stripMargin,
      example = "\"none\"",
      defaultValue = FallbackSourcepathConfig.AllSources,
      choices = List(
        "allSources" -> FallbackSourcepathConfig.AllSources,
        "none" -> FallbackSourcepathConfig.None,
      ),
      fromFeatureFlag = featureFlags => {
        val isAllSourcesEnabled = featureFlags
          .readBooleanOrFalse(FeatureFlag.FULL_SOURCEPATH_FALLBACK_SCALA)
        if (isAllSourcesEnabled) {
          Some(FallbackSourcepathConfig.AllSources)
        } else {
          None
        }
      },
    )(
      config => config.fallbackSourcepath,
      (config, value) => config.copy(fallbackSourcepath = value),
    ),
  )

  private def symbolPrefixes(
      key: String,
      title: String,
      description: String,
      example: String,
      default: String,
  ): UserConfigurationOption[Map[String, String]] =
    CustomConfigurationOption[Map[String, String]](
      key,
      title,
      description,
      example,
      default,
    )(
      _.symbolPrefixes,
      (config, value) => config.copy(symbolPrefixes = value),
    )(
      readValue = context => {
        val prefixes = context
          .getStringMap("symbol-prefixes")
          .getOrElse(
            PresentationCompilerConfig
              .defaultSymbolPrefixes()
              .asScala
              .toMap
          )
        for (symbol <- prefixes.keys) {
          Symbol.validated(symbol) match {
            case Left(error) => context.addError(error)
            case Right(_) =>
          }
        }
        prefixes
      },
      writeValue = prefixes =>
        Some(
          prefixes.map { case (key, value) =>
            key.toString() -> value.toString()
          }.asJava
        ),
    )

  private def shimGlobs(
      key: String,
      title: String,
      description: String,
      example: String,
      default: String,
  ): UserConfigurationOption[Map[String, List[String]]] =
    CustomConfigurationOption[Map[String, List[String]]](
      key,
      title,
      description,
      example,
      default,
    )(_.shimGlobs, (config, value) => config.copy(shimGlobs = value))(
      readValue = context => {
        val userGlobs =
          context.getStringListMap("shim-globs").getOrElse(Map.empty)
        val flagGlobs =
          context.featureFlags
            .readStringList(FeatureFlag.SHIM_GLOBS)
            .asScala
            .toList
        if (flagGlobs.isEmpty) userGlobs
        else userGlobs + ("_default" -> flagGlobs)
      },
      writeValue = globs =>
        Some(
          globs.map { case (key, values) =>
            key.toString() -> values.map(_.toString()).asJava
          }.asJava
        ),
    )

}
