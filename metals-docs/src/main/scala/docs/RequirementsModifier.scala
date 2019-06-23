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
       |**OpenJDK 8 or OpenJDK 11**. Eclipse OpenJ9 is not supported,
       |please make sure the `JAVA_HOME` environment variable points to a
       |valid Java 8 or Java 11 installation.
       |
       |**macOS, Linux or Windows**. Metals is developed on macOS and every PR is
       |tested on Ubuntu+Windows.
       |
       |**Scala 2.13, 2.12 and 2.11**. Metals supports these Scala versions $supportedScalaVersions.
       |Note that 2.11.x support is deprecated and users are recommended to upgrade to Scala 2.12 to
       |enjoy the latest improvements in the compiler.
       |""".stripMargin
  }
}
