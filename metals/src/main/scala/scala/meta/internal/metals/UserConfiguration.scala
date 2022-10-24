package scala.meta.internal.metals

import java.nio.file.Paths
import java.util.Properties

import scala.collection.mutable.ListBuffer
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.mtags.Symbol
import scala.meta.io.AbsolutePath
import scala.meta.pc.PresentationCompilerConfig

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
    bloopJvmProperties: Option[List[String]] = None,
    ammoniteJvmProperties: Option[List[String]] = None,
    superMethodLensesEnabled: Boolean = false,
    showInferredType: Boolean = false,
    showImplicitArguments: Boolean = false,
    showImplicitConversionsAndClasses: Boolean = false,
    remoteLanguageServer: Option[String] = None,
    enableStripMarginOnTypeFormatting: Boolean = true,
    enableIndentOnPaste: Boolean = false,
    excludedPackages: Option[List[String]] = None,
    fallbackScalaVersion: Option[String] = None,
    testUserInterface: TestUserInterfaceKind = TestUserInterfaceKind.CodeLenses,
    javaFormatConfig: Option[JavaFormatConfig] = None,
    scalafixRulesDependencies: List[String] = Nil,
    scalaCliLauncher: Option[String] = None,
) {

  def currentBloopVersion: String =
    bloopVersion.getOrElse(BuildInfo.bloopVersion)

  def usedJavaBinary(): Option[AbsolutePath] = {
    javaHome
      .orElse(
        JdkSources.defaultJavaHome
      )
      .map(home => AbsolutePath(Paths.get(home).resolve("bin/java")))
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
        """Optional absolute path to a `mill` executable to use for running `mill mill.contrib.Bloop/install`.
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
        "ammonite-jvm-properties",
        """`[]`.""",
        """["-Xmx1G"]""",
        "Ammonite JVM Properties",
        """|Optional list of JVM properties to pass along to the Ammonite server.
           |Each property needs to be a separate item.\n\nExample: `-Xmx1G` or `-Xms100M`"
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
        "show-inferred-type",
        "false",
        "false",
        "Should display type annotations for inferred types",
        """|When this option is enabled, each method that can have inferred types has them
           |displayed either as additional decorations if they are supported by the editor or
           |shown in the hover.
           |""".stripMargin,
      ),
      UserConfigurationOption(
        "show-implicit-arguments",
        "false",
        "false",
        "Should display implicit parameter at usage sites",
        """|When this option is enabled, each method that has implicit arguments has them 
           |displayed either as additional decorations if they are supported by the editor or 
           |shown in the hover.
           |""".stripMargin,
      ),
      UserConfigurationOption(
        "show-implicit-conversions-and-classes",
        "false",
        "false",
        "Should display implicit conversion at usage sites",
        """|When this option is enabled, each place where an implicit method or class is used has it 
           |displayed either as additional decorations if they are supported by the editor or 
           |shown in the hover.
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
        "remote-language-server",
        """empty string `""`.""",
        """"https://language-server.company.com/message"""",
        "Remote language server",
        """A URL pointing to an endpoint that implements a remote language server.
          |
          |See https://scalameta.org/metals/docs/integrations/remote-language-server for
          |documentation on remote language servers.
          |""".stripMargin,
      ),
      UserConfigurationOption(
        "fallback-scala-version",
        BuildInfo.scala3,
        BuildInfo.scala3,
        "Default fallback Scala version",
        """|The Scala compiler version that is used as the default or fallback in case a file 
           |doesn't belong to any build target or the specified Scala version isn't supported by Metals.
           |This applies to standalone Scala files, worksheets, and Ammonite scripts.
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
    val ammoniteProperties = getStringListKey("ammonite-jvm-properties")
    val bloopSbtAlreadyInstalled =
      getBooleanKey("bloop-sbt-already-installed").getOrElse(false)
    val bloopVersion =
      getStringKey("bloop-version")
    val bloopJvmProperties = getStringListKey("bloop-jvm-properties")
    val superMethodLensesEnabled =
      getBooleanKey("super-method-lenses-enabled").getOrElse(false)
    val showInferredType =
      getBooleanKey("show-inferred-type").getOrElse(false)
    val showImplicitArguments =
      getBooleanKey("show-implicit-arguments").getOrElse(false)
    val showImplicitConversionsAndClasses =
      getBooleanKey("show-implicit-conversions-and-classes").getOrElse(false)
    val remoteLanguageServer =
      getStringKey("remote-language-server")
    val enableStripMarginOnTypeFormatting =
      getBooleanKey("enable-strip-margin-on-type-formatting").getOrElse(true)
    val enableIndentOnPaste =
      getBooleanKey("enable-indent-on-paste").getOrElse(true)
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
          ammoniteProperties,
          superMethodLensesEnabled,
          showInferredType,
          showImplicitArguments,
          showImplicitConversionsAndClasses,
          remoteLanguageServer,
          enableStripMarginOnTypeFormatting,
          enableIndentOnPaste,
          excludedPackages,
          defaultScalaVersion,
          disableTestCodeLenses,
          javaFormatConfig,
          scalafixRulesDependencies,
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
  object CodeLenses extends TestUserInterfaceKind
  object TestExplorer extends TestUserInterfaceKind
}
