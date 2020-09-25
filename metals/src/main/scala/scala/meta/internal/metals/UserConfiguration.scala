package scala.meta.internal.metals

import java.util.Properties

import scala.collection.mutable.ListBuffer
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import scala.meta.RelativePath
import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.mtags.Symbol
import scala.meta.pc.PresentationCompilerConfig

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/**
 * Configuration that the user can override via workspace/didChangeConfiguration.
 */
case class UserConfiguration(
    javaHome: Option[String] = None,
    sbtScript: Option[String] = None,
    gradleScript: Option[String] = None,
    mavenScript: Option[String] = None,
    millScript: Option[String] = None,
    scalafmtConfigPath: RelativePath = RelativePath(".scalafmt.conf"),
    scalafixConfigPath: RelativePath = RelativePath(".scalafix.conf"),
    symbolPrefixes: Map[String, String] =
      PresentationCompilerConfig.defaultSymbolPrefixes().asScala.toMap,
    worksheetScreenWidth: Int = 120,
    worksheetCancelTimeout: Int = 4,
    bloopSbtAlreadyInstalled: Boolean = false,
    bloopVersion: Option[String] = None,
    ammoniteJvmProperties: Option[List[String]] = None,
    superMethodLensesEnabled: Boolean = false,
    remoteLanguageServer: Option[String] = None,
    enableStripMarginOnTypeFormatting: Boolean = true,
    excludedPackages: Option[List[String]] = None
) {

  def currentBloopVersion: String =
    bloopVersion.getOrElse(BuildInfo.bloopVersion)

}

object UserConfiguration {

  def default: UserConfiguration = UserConfiguration()

  private val defaultExclusion = new ExcludedPackagesHandler(
    None
  ).defaultExclusions.map(_.dropRight(1)).mkString("\n").replace("/", ".")

  def options: List[UserConfigurationOption] =
    List(
      UserConfigurationOption(
        "java-home",
        "`JAVA_HOME` environment variable with fallback to `user.home` system property.",
        """"/Library/Java/JavaVirtualMachines/jdk1.8.0_192.jdk/Contents/Home"""",
        "Java Home directory",
        "The Java Home directory used for indexing JDK sources and locating the `java` binary."
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
          |""".stripMargin
      ),
      UserConfigurationOption(
        "gradle-script",
        """empty string `""`.""",
        """"/usr/local/bin/gradle"""",
        "gradle script",
        """Optional absolute path to a `gradle` executable to use for running `gradle bloopInstall`.
          |By default, Metals uses gradlew with 5.3.1 gradle version. Update this setting if your `gradle` script requires more customizations
          |like using environment variables.
          |""".stripMargin
      ),
      UserConfigurationOption(
        "maven-script",
        """empty string `""`.""",
        """"/usr/local/bin/mvn"""",
        "maven script",
        """Optional absolute path to a `maven` executable to use for generating bloop config.
          |By default, Metals uses mvnw maven wrapper with 3.6.1 maven version. Update this setting if your `maven` script requires more customizations
          |""".stripMargin
      ),
      UserConfigurationOption(
        "mill-script",
        """empty string `""`.""",
        """"/usr/local/bin/mill"""",
        "mill script",
        """Optional absolute path to a `mill` executable to use for running `mill mill.contrib.Bloop/install`.
          |By default, Metals uses mill wrapper script with 0.5.0 mill version. Update this setting if your `mill` script requires more customizations
          |like using environment variables.
          |""".stripMargin
      ),
      UserConfigurationOption(
        "scalafmt-config-path",
        default.scalafmtConfigPath.toString,
        """"project/.scalafmt.conf"""",
        "Scalafmt config path",
        """Optional custom path to the .scalafmt.conf file.
          |Should be relative to the workspace root directory and use forward slashes / for file
          |separators (even on Windows).
          |""".stripMargin
      ),
      UserConfigurationOption(
        "ammonite-jvm-properties",
        """`[]`.""",
        """["-Xmx1G"]""",
        "Ammonite JVM Properties",
        """|Optional list of JVM properties to pass along to the Ammonite server.
           |Each property needs to be a separate item.\n\nExample: `-Xmx1G` or `-Xms100M`"
           |""".stripMargin
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
            |""".stripMargin
      ),
      UserConfigurationOption(
        "bloop-sbt-already-installed",
        "false",
        "false",
        "Don't generate Bloop plugin file for sbt",
        "If true, Metals will not generate `metals.sbt` files under the assumption that sbt-bloop is already manually installed in the sbt build. Build import will fail with a 'not valid command bloopInstall' error in case Bloop is not manually installed in the build when using this option."
      ),
      UserConfigurationOption(
        "bloop-version",
        BuildInfo.bloopVersion,
        """"1.4.0-RC1"""",
        "Version of Bloop",
        """|This version will be used for the Bloop build tool plugin, for any supported build tool,
           |while importing in Metals as well as for running the embedded server""".stripMargin
      ),
      UserConfigurationOption(
        "super-method-lenses-enabled",
        "false",
        "false",
        "Should display lenses with links to super methods",
        """|Super method lenses are visible above methods definition that override another methods. Clicking on a lens jumps to super method definition.
           |Disabled lenses are not calculated for opened documents which might speed up document processing.
           |
           |""".stripMargin
      ),
      UserConfigurationOption(
        "remote-language-server",
        """empty string `""`.""",
        """"https://language-server.company.com/message"""",
        "Remote language server",
        """A URL pointing to an endpoint that implements a remote language server.
          |
          |See https://scalameta.org/metals/docs/contributors/remote-language-server.html for
          |documentation on remote language servers.
          |""".stripMargin
      )
    )

  def fromJson(
      json: JsonObject,
      properties: Properties = System.getProperties
  ): Either[List[String], UserConfiguration] = {
    val errors = ListBuffer.empty[String]
    val base: JsonObject =
      Option(json.getAsJsonObject("metals")).getOrElse(new JsonObject)

    def getKey[A](key: String, f: JsonElement => Option[A]): Option[A] = {
      def option[T](fn: String => T): Option[T] =
        Option(fn(key)).orElse(Option(fn(StringCase.kebabToCamel(key))))
      for {
        jsonValue <- option(k => properties.getProperty(s"metals.$k"))
          .filterNot(_.isEmpty())
          .map(prop => new JsonPrimitive(prop))
          .orElse(option(base.get))
        value <- f(jsonValue)
      } yield value
    }

    def getStringKey(key: String): Option[String] =
      getKey(
        key,
        { value =>
          Try(value.getAsString)
            .fold(
              _ => {
                errors += s"json error: key '$key' should have value of type string but obtained $value"
                None
              },
              Some(_)
            )
            .filter(_.nonEmpty)
        }
      )

    def getBooleanKey(key: String): Option[Boolean] =
      getKey(
        key,
        { value =>
          Try(value.getAsBoolean())
            .fold(
              _ => {
                errors += s"json error: key '$key' should have value of type boolean but obtained $value"
                None
              },
              Some(_)
            )
        }
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
        }
      )

    def getStringMap(key: String): Option[Map[String, String]] =
      getKey(
        key,
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
            entries => Some(entries.toMap)
          ).filter(_.nonEmpty)
        }
      )

    val javaHome =
      getStringKey("java-home")
    val scalafmtConfigPath =
      getStringKey("scalafmt-config-path")
        .map(RelativePath(_))
        .getOrElse(default.scalafmtConfigPath)
    val scalafixConfigPath =
      getStringKey("scalafix-config-path")
        .map(RelativePath(_))
        .getOrElse(default.scalafixConfigPath)
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
    val superMethodLensesEnabled =
      getBooleanKey("super-method-lenses-enabled").getOrElse(false)
    val remoteLanguageServer =
      getStringKey("remote-language-server")
    val enableStripMarginOnTypeFormatting =
      getBooleanKey("enable-strip-margin-on-type-formatting").getOrElse(true)
    val excludedPackages =
      getStringListKey("excluded-packages")
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
          ammoniteProperties,
          superMethodLensesEnabled,
          remoteLanguageServer,
          enableStripMarginOnTypeFormatting,
          excludedPackages
        )
      )
    } else {
      Left(errors.toList)
    }
  }

  def parse(config: String): JsonObject = {
    import JsonParser._
    s"""{"metals": $config}""".parseJson.getAsJsonObject
  }

}
