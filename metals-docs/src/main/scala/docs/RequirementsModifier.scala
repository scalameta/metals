package docs

import mdoc.Reporter
import mdoc.StringModifier
import scala.meta.inputs.Input
import scala.meta.internal.metals.BuildInfo

class RequirementsModifier extends StringModifier {
  override val name: String = "requirements"

  def supportedScalaVersions =
    BuildInfo.supportedScalaVersions.dropRight(1).mkString(", ") +
      s" and ${BuildInfo.supportedScalaVersions.last}"

  override def process(
      info: String,
      code: Input,
      reporter: Reporter
  ): String = {
    s"""
       |## Requirements
       |
       |**Java 8**. Metals does not work with Java 11 yet so make sure the JAVA_HOME
       |environment variable points to Java 8.
       |
       |**macOS, Linux or Windows**. Metals is developed on macOS and every PR is
       |tested on Ubuntu+Windows.
       |
       |**Scala 2.12 and 2.11**. Metals works only with Scala versions $supportedScalaVersions.
       |Note that 2.10.x and 2.13.0-M5 are not supported.
       |""".stripMargin
  }
}
