package scala.meta.internal.metals

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.util.Properties
import scala.meta.internal.jdk.CollectionConverters._
import scala.collection.mutable.ListBuffer
import scala.meta.RelativePath
import scala.meta.internal.mtags.Symbol
import scala.meta.pc.PresentationCompilerConfig
import scala.util.Try

/**
 * Configuration that the user can override via workspace/didChangeConfiguration.
 *
 * @param javaHome The Java home location used to detect src.zip for JDK sources.
 */
case class UserConfiguration(
    javaHome: Option[String] = None,
    sbtScript: Option[String] = None,
    gradleScript: Option[String] = None,
    mavenScript: Option[String] = None,
    millScript: Option[String] = None,
    scalafmtConfigPath: RelativePath =
      UserConfiguration.default.scalafmtConfigPath,
    symbolPrefixes: Map[String, String] =
      UserConfiguration.default.symbolPrefixes,
    worksheetScreenWidth: Int = 120,
    worksheetCancelTimeout: Int = 4
)
object UserConfiguration {

  object default {
    def scalafmtConfigPath: RelativePath = RelativePath(".scalafmt.conf")
    def symbolPrefixes: Map[String, String] =
      PresentationCompilerConfig.defaultSymbolPrefixes().asScala.toMap
  }

  def options: List[UserConfigurationOption] = List(
    UserConfigurationOption(
      "java-home",
      "`JAVA_HOME` environment variable with fallback to `user.home` system property.",
      "/Library/Java/JavaVirtualMachines/jdk1.8.0_192.jdk/Contents/Home",
      "Java Home directory",
      "The Java Home directory used for indexing JDK sources and locating the `java` binary."
    ),
    UserConfigurationOption(
      "sbt-script",
      """empty string `""`.""",
      "/usr/local/bin/sbt",
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
      "/usr/local/bin/gradle",
      "gradle script",
      """Optional absolute path to a `gradle` executable to use for running `gradle bloopInstall`.
        |By default, Metals uses gradlew with 5.3.1 gradle version. Update this setting if your `gradle` script requires more customizations
        |like using environment variables.
        |""".stripMargin
    ),
    UserConfigurationOption(
      "maven-script",
      """empty string `""`.""",
      "/usr/local/bin/mvn",
      "maven script",
      """Optional absolute path to a `maven` executable to use for generating bloop config.
        |By default, Metals uses mvnw maven wrapper with 3.6.1 maven version. Update this setting if your `maven` script requires more customizations
        |""".stripMargin
    ),
    UserConfigurationOption(
      "mill-script",
      """empty string `""`.""",
      "/usr/local/bin/mill",
      "mill script",
      """Optional absolute path to a `mill` executable to use for running `mill mill.contrib.Bloop/install`.
        |By default, Metals uses mill wrapper script with 0.5.0 mill version. Update this setting if your `mill` script requires more customizations
        |like using environment variables.
        |""".stripMargin
    ),
    UserConfigurationOption(
      "scalafmt-config-path",
      default.scalafmtConfigPath.toString,
      "project/.scalafmt.conf",
      "Scalafmt config path",
      """Optional custom path to the .scalafmt.conf file.
        |Should be relative to the workspace root directory and use forward slashes / for file
        |separators (even on Windows).
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
        jsonValue <- option(base.get)
          .orElse(
            option(k => properties.getProperty(s"metals.$k"))
              .map(new JsonPrimitive(_))
          )
        value <- f(jsonValue)
      } yield value
    }

    def getStringKey(key: String): Option[String] =
      getKey(
        key, { value =>
          Try(value.getAsString)
            .fold(_ => {
              errors += s"json error: key '$key' should have value of type string but obtained $value"
              None
            }, Some(_))
            .filter(_.nonEmpty)
        }
      )
    def getIntKey(key: String): Option[Int] =
      getStringKey(key).flatMap { value =>
        Try(value.toInt) match {
          case Failure(exception) =>
            errors += s"Not a number: '$value'"
            None
          case Success(value) =>
            Some(value)
        }
      }
    def getStringMap(key: String): Option[Map[String, String]] =
      getKey(
        key, { value =>
          Try {
            for {
              entry <- value.getAsJsonObject.entrySet().asScala.iterator
              if entry.getValue.isJsonPrimitive &&
                entry.getValue.getAsJsonPrimitive.isString
            } yield {
              entry.getKey -> entry.getValue.getAsJsonPrimitive.getAsString
            }
          }.fold(_ => {
              errors += s"json error: key '$key' should have be object with string values but obtained $value"
              None
            }, entries => Some(entries.toMap))
            .filter(_.nonEmpty)
        }
      )

    val javaHome =
      getStringKey("java-home")
    val scalafmtConfigPath =
      getStringKey("scalafmt-config-path")
        .map(RelativePath(_))
        .getOrElse(default.scalafmtConfigPath)
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

    if (errors.isEmpty) {
      Right(
        UserConfiguration(
          javaHome,
          sbtScript,
          gradleScript,
          mavenScript,
          millScript,
          scalafmtConfigPath,
          symbolPrefixes,
          worksheetScreenWidth,
          worksheetCancelTimeout
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
