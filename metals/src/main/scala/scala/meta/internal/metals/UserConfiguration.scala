package scala.meta.internal.metals

import com.google.gson.JsonObject
import scala.collection.mutable.ListBuffer
import scala.util.control.NonFatal

/**
 * Configuration that the user can override via workspace/didChangeConfiguration.
 *
 * @param javaHome The Java home location used to detect src.zip for JDK sources.
 */
case class UserConfiguration(
    javaHome: Option[String] = None
)

object UserConfiguration {
  def fromJson(
      base: UserConfiguration,
      json: JsonObject
  ): Either[List[String], UserConfiguration] = {
    val errors = ListBuffer.empty[String]

    val javaHome: Option[String] =
      if (json.has("javaHome")) {
        try {
          val string = json.get("javaHome").getAsString
          if (string.isEmpty) None
          else Some(string)
        } catch {
          case NonFatal(e) =>
            errors += e.getMessage
            base.javaHome
        }
      } else {
        base.javaHome
      }

    if (errors.isEmpty) {
      Right(
        UserConfiguration(
          javaHome
        )
      )
    } else {
      Left(errors.toList)
    }
  }
}
