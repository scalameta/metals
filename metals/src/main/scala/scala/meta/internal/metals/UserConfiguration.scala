package scala.meta.internal.metals

import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
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
    sbtLauncher: Option[String] = None,
    sbtOpts: List[String] = Nil,
    sbtScript: Option[String] = None
)

object UserConfiguration {
  def options: List[UserConfigurationOption] = List(
    UserConfigurationOption(
      "java-home",
      "`JAVA_HOME` environment variable with fallback to `user.home` system property.",
      "/Library/Java/JavaVirtualMachines/jdk1.8.0_192.jdk/Contents/Home",
      "Java Home directory",
      "The Java Home directory used for indexing JDK sources and locating the `java` binary."
    ),
    UserConfigurationOption(
      "sbt-launcher",
      "Metals embedded sbt-launch.jar.",
      "/usr/local/Cellar/sbt/1.2.6/libexec/bin/sbt-launch.jar",
      "sbt launcher jar",
      "Optional sbt-launch.jar launcher to use when running `sbt bloopInstall`."
    ),
    UserConfigurationOption(
      "sbt-options",
      """empty string `""`.""",
      "-Dsbt.override.build.repos=true -Divy.home=/home/ivy-cache",
      "sbt JVM options",
      """Additional space separated JVM options used for the `sbt bloopInstall` step.
        |By default, Metals respects custom options in `.jvmopts` and `.sbtopts` of the workspace root directory,
        |it's recommended to use those instead of customizing this setting. The benefit of `.jvmopts` and `.sbtopts`
        |is that it's respected by other tools such as IntelliJ.
        |""".stripMargin
    ),
    UserConfigurationOption(
      "sbt-script",
      """empty string `""`.""",
      "/usr/local/bin/sbt",
      "sbt script",
      """Custom `sbt` executable to use for running `sbt bloopInstall`, overrides
        |`sbt-options` and `sbt-launcher` options.
        |
        |By default, Metals uses `java -jar sbt-launch.jar` to launch sbt while respecting
        |`.jvmopts` and `.sbtopts`. In case this option is defined, then Metals
        |executes the configured sbt script instead.
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

    def getKey(key: String): Option[String] = {
      def option[T](fn: String => T): Option[T] =
        Option(fn(key)).orElse(Option(fn(StringCase.kebabToCamel(key))))
      for {
        value <- option(base.get)
          .orElse(
            option(k => properties.getProperty(s"metals.$k"))
              .map(new JsonPrimitive(_))
          )
        string <- Try(value.getAsString).fold(
          _ => {
            errors += s"json error: key '$key' should have value of type string but obtained $value"
            None
          },
          Some(_)
        )
        if string.nonEmpty
      } yield string
    }

    val javaHome =
      getKey("java-home")
    val sbtLauncher =
      getKey("sbt-launcher")
    val sbtOpts =
      getKey("sbt-options") match {
        case Some(value) => value.split(" ").toList
        case None => Nil
      }
    val sbtScript =
      getKey("sbt-script")

    if (errors.isEmpty) {
      Right(
        UserConfiguration(
          javaHome,
          sbtLauncher,
          sbtOpts,
          sbtScript
        )
      )
    } else {
      Left(errors.toList)
    }
  }

  def toWrappedJson(config: String): String =
    s"""{"metals": $config}"""
}
