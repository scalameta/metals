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
    sbtScript: Option[String] = None,
    compileOnSave: String = UserConfiguration.CascadeCompile
) {
  def isCascadeCompile: Boolean =
    compileOnSave == UserConfiguration.CascadeCompile
  def isCurrentProject: Boolean =
    compileOnSave == UserConfiguration.CurrentProjectCompile
}

object UserConfiguration {
  val CascadeCompile = "cascade"
  val CurrentProjectCompile = "current-project"
  def allCompile: List[String] =
    List(CascadeCompile, CurrentProjectCompile)
  def options: List[UserConfigurationOption] = List(
    UserConfigurationOption(
      "compile-on-save",
      s""" `"$CascadeCompile"` """,
      CurrentProjectCompile,
      "Compile on save",
      """What compilation mode to use for file save events.
        |Possible values:
        |
        |- `"cascade"` (default): compile the build target that contains the saved file
        |  along other build targets that depend on that build target.
        |- `"current-project"`: compile only the build target that contains the saved file.
      """.stripMargin
    ),
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
    val sbtScript =
      getKey("sbt-script")
    val cascadeCompile = getKey("compile-on-save") match {
      case None => CascadeCompile
      case Some(value) =>
        value match {
          case CascadeCompile | CurrentProjectCompile => value
          case unknown =>
            errors += s"unknown compile-on-save: '$unknown'. Expected one of: ${allCompile.mkString(", ")}."
            CascadeCompile
        }
    }

    if (errors.isEmpty) {
      Right(
        UserConfiguration(
          javaHome,
          sbtScript,
          cascadeCompile
        )
      )
    } else {
      Left(errors.toList)
    }
  }

  def toWrappedJson(config: String): String =
    s"""{"metals": $config}"""

}
