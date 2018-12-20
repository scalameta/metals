package scala.meta.internal.metals

import scala.meta.RelativePath
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.JsonElement
import java.util.Properties
import scala.collection.mutable.ListBuffer
import scala.util.Try

/**
 * Configuration that the user can override via workspace/didChangeConfiguration.
 *
 * @param javaHome The Java home location used to detect src.zip for JDK sources.
 */
case class UserConfiguration(
    javaHome: Option[String] = None,
    sbtScript: Option[String] = None,
    scalafmtConfigPath: RelativePath =
      UserConfiguration.default.scalafmtConfigPath
)

object UserConfiguration {
  object default {
    val scalafmtConfigPath = RelativePath(".scalafmt.conf")
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
      "scalafmt-config-path",
      default.scalafmtConfigPath.toString,
      "project/.scalafmt.conf",
      "Scalafmt config path",
      "Optional Scalafmt config file path, relative to the workspace root"
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
        key,
        value =>
          Try(value.getAsString)
            .fold(_ => {
              errors += s"json error: key '$key' should have value of type string but obtained $value"
              None
            }, Some(_))
            .filter(_.nonEmpty)
      )

    val javaHome =
      getStringKey("java-home")
    val sbtScript =
      getStringKey("sbt-script")
    val scalafmtConfigPath =
      getStringKey("scalafmt-config-path")
        .map(RelativePath(_))
        .getOrElse(default.scalafmtConfigPath)

    if (errors.isEmpty) {
      Right(
        UserConfiguration(
          javaHome,
          sbtScript,
          scalafmtConfigPath
        )
      )
    } else {
      Left(errors.toList)
    }
  }

  def toWrappedJson(config: String): String =
    s"""{"metals": $config}"""
}
