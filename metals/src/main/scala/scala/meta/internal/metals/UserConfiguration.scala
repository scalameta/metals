package scala.meta.internal.metals

import java.util.Properties

import scala.collection.mutable.ListBuffer
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import scala.meta.internal.metals.JsonParser.XtensionSerializedAsOption
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.Symbol
import scala.meta.io.AbsolutePath
import scala.meta.pc.PresentationCompilerConfig

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
    worksheetScreenWidth: Int = 120,
    worksheetCancelTimeout: Int = 4,
    bloopSbtAlreadyInstalled: Boolean = false,
    bloopVersion: Option[String] = None,
    bloopJvmProperties: BloopJvmProperties = BloopJvmProperties.Uninitialized,
    superMethodLensesEnabled: Boolean = false,
    inlayHintsOptions: InlayHintsOptions = InlayHintsOptions(Map.empty),
    enableStripMarginOnTypeFormatting: Boolean = true,
    enableIndentOnPaste: Boolean = false,
    enableSemanticHighlighting: Boolean = true,
    excludedPackages: Option[List[String]] = None,
    fallbackScalaVersion: Option[String] = None,
    testUserInterface: TestUserInterfaceKind = TestUserInterfaceKind.CodeLenses,
    javaFormatConfig: Option[JavaFormatConfig] = None,
    scalafixRulesDependencies: List[String] = Nil,
    customProjectRoot: Option[String] = None,
    verboseCompilation: Boolean = false,
    automaticImportBuild: AutoImportBuildKind = AutoImportBuildKind.Off,
    targetBuildTool: Option[String] = None,
    scalaCliLauncher: Option[String] = None,
    defaultBspToBuildTool: Boolean = false,
    enableBestEffort: Boolean = false,
    defaultShell: Option[String] = None,
    startMcpServer: Boolean = false,
    mcpClient: Option[String] = None,
) {

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

    val fields = List(
      optStringField("javaHome", javaHome),
      optStringField("sbtScript", sbtScript),
      optStringField("gradleScript", gradleScript),
      optStringField("mavenScript", mavenScript),
      optStringField("millScript", millScript),
      optStringField("scalafmtConfigPath", scalafmtConfigPath),
      optStringField("scalafixConfigPath", scalafixConfigPath),
      mapField("symbolPrefixes", symbolPrefixes),
      Some(("worksheetScreenWidth", worksheetScreenWidth)),
      Some(("worksheetCancelTimeout", worksheetCancelTimeout)),
      Some(("bloopSbtAlreadyInstalled", bloopSbtAlreadyInstalled)),
      optStringField("bloopVersion", bloopVersion),
      listField("bloopJvmProperties", bloopJvmProperties.properties),
      Some(("superMethodLensesEnabled", superMethodLensesEnabled)),
      mapField("inlayHintsOptions", inlayHintsOptions.options),
      Some(
        (
          "enableStripMarginOnTypeFormatting",
          enableStripMarginOnTypeFormatting,
        )
      ),
      Some(("enableIndentOnPaste", enableIndentOnPaste)),
      Some(
        (
          "enableSemanticHighlighting",
          enableSemanticHighlighting,
        )
      ),
      listField("excludedPackages", excludedPackages),
      optStringField("fallbackScalaVersion", fallbackScalaVersion),
      Some("testUserInterface" -> testUserInterface.toString()),
      javaFormatConfig.map(value =>
        "javaFormat" -> List(
          Some("eclipseConfigPath" -> value.eclipseFormatConfigPath.toString()),
          value.eclipseFormatProfile.map("eclipseProfile" -> _),
        ).flatten.toMap.asJava
      ),
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
      optStringField("targetBuildTool", targetBuildTool),
      optStringField("scalaCliLauncher", scalaCliLauncher),
      Some(
        (
          "defaultBspToBuildTool",
          defaultBspToBuildTool,
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
    ).flatten.toMap.asJava
    val gson = new GsonBuilder().setPrettyPrinting().create()
    gson.toJson(fields).toString()
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
        "scalafix-rules-dependencies",
        """`[]`""",
        """["com.github.liancheng::organize-imports:0.6.0"]""",
        "Scalafix rules dependencies",
        """Optional list of Scalafix rules dependencies to use for running `scalafix --rules`.""",
        isArray = true,
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
        isArray = true,
      ),
      UserConfigurationOption(
        "bloop-sbt-already-installed",
        "false",
        "false",
        "Don't generate Bloop plugin file for sbt",
        "If true, Metals will not generate `metals.sbt` files under the assumption that sbt-bloop is already manually installed in the sbt build. Build import will fail with a 'not valid command bloopInstall' error in case Bloop is not manually installed in the build when using this option.",
        isBoolean = true,
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
        isArray = true,
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
        isBoolean = true,
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
        isBoolean = true,
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
        isBoolean = true,
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
        isBoolean = true,
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
        isBoolean = true,
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
        isBoolean = true,
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
        isBoolean = true,
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
        isBoolean = true,
      ),
      UserConfigurationOption(
        "inlay-hints.hints-x-ray-mode.enable",
        "false",
        "false",
        "Should display type annotations for intermediate types of multi-line expressions",
        """|When this option is enabled, each method/attribute call in a multi-line chain will get
           | its own type annotation.
           |""".stripMargin,
        isBoolean = true,
      ),
      UserConfigurationOption(
        "inlay-hints.closing-labels.enable",
        "false",
        "false",
        "Should display closing label hints for methods/classes/objects next to their closing braces",
        """|When this option is enabled, each method/class/object definition that uses braces syntax,
           | will get a closing label hint next to the closing brace with the name of the definition.
           |""".stripMargin,
        isBoolean = true,
      ),
      UserConfigurationOption(
        "enable-semantic-highlighting",
        "true",
        "false",
        "Use semantic tokens highlight",
        """|When this option is enabled, Metals will provide semantic tokens for clients that support it.
           |The feature should work within all supported files extensions aside from Java.
           |""".stripMargin,
        isBoolean = true,
      ),
      UserConfigurationOption(
        "enable-indent-on-paste",
        "false",
        "false",
        "Indent snippets when pasted.",
        """|When this option is enabled, when a snippet is pasted into a Scala file, Metals will
           |try to adjust the indentation to that of the current cursor.
           |""".stripMargin,
        isBoolean = true,
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
        isBoolean = true,
      ),
      UserConfigurationOption(
        "auto-import-builds",
        "off",
        "all",
        "Import build when changes detected without prompting",
        """|Automatically import builds rather than prompting the user to choose. "initial" will 
           |only automatically import a build when a project is first opened, "all" will automate 
           |build imports after subsequent changes as well.""".stripMargin,
      ),
      UserConfigurationOption(
        "target-build-tool",
        """empty string `""`.""",
        """"bazel"""",
        "Preferred build tool when multiple are detected",
        """|The preferred build tool to use when multiple build definitions are detected in the workspace.
           |This prevents the build tool selection dialog from appearing on startup.
           |Valid values are: "sbt", "gradle", "mvn", "mill", "scala-cli", "bazel".
           |""".stripMargin,
      ),
      UserConfigurationOption(
        "default-bsp-to-build-tool",
        "false",
        "true",
        "Default to using build tool as your build server.",
        """|If your build tool can also serve as a build server,
           |default to using it instead of Bloop.
           |""".stripMargin,
        isBoolean = true,
      ),
      UserConfigurationOption(
        "enable-best-effort",
        "false",
        "true",
        "Use best effort compilation for Scala 3.",
        """|When using Scala 3, use best effort compilation to improve Metals 
           |correctness when the workspace doesn't compile.
           |""".stripMargin,
        isBoolean = true,
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
        isBoolean = true,
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
    val enableSemanticHighlighting =
      getBooleanKey("enable-semantic-highlighting").getOrElse(true)
    val excludedPackages =
      getStringListKey("excluded-packages")
    // `automatic` should be treated as None
    // It was added only to have a meaningful option value in vscode
    val defaultScalaVersion =
      getStringKey("fallback-scala-version").filter(_ != "automatic")
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

    val targetBuildTool = {
      import scala.meta.internal.builds._
      getStringKey("target-build-tool").flatMap { tool =>
        if (BuildTools.allBuildToolNames.contains(tool)) {
          Some(tool)
        } else {
          errors += s"Invalid target-build-tool '$tool'. Valid values are: ${BuildTools.allBuildToolNames.toSeq.sorted.mkString(", ")}"
          None
        }
      }
    }

    val scalaCliLauncher = getStringKey("scala-cli-launcher")
    val defaultBspToBuildTool =
      getBooleanKey("default-bsp-to-build-tool").getOrElse(false)

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
          worksheetScreenWidth,
          worksheetCancelTimeout,
          bloopSbtAlreadyInstalled,
          bloopVersion,
          bloopJvmProperties,
          superMethodLensesEnabled,
          inlayHintsOptions,
          enableStripMarginOnTypeFormatting,
          enableIndentOnPaste,
          enableSemanticHighlighting,
          excludedPackages,
          defaultScalaVersion,
          disableTestCodeLenses,
          javaFormatConfig,
          scalafixRulesDependencies,
          customProjectRoot,
          verboseCompilation,
          autoImportBuilds,
          targetBuildTool,
          scalaCliLauncher,
          defaultBspToBuildTool,
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
