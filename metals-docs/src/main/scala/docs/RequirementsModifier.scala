package docs

import scala.concurrent.duration._

import scala.meta.inputs.Input
import scala.meta.metals.SupportedScalaVersions

import mdoc.Reporter
import mdoc.StringModifier

class RequirementsModifier extends SupportedScalaVersions with StringModifier {
  override val name: String = "requirements"

  override protected def formatSingle(
      major: String,
      versions: Seq[String],
  ): String = {
    s"""| - **Scala ${major}**:
        |   ${versions.mkString(", ")}
        |
        |""".stripMargin
  }

  override def process(
      info: String,
      code: Input,
      reporter: Reporter,
  ): String = {
    val isVScode = info == "vscode"
    val javaRequirement =
      if (isVScode) ""
      else
        s"""
           |
           |**Java 11, 17 provided by OpenJDK or Oracle**. Eclipse OpenJ9 is not
           |supported, please make sure the `JAVA_HOME` environment variable
           |points to a valid Java 11 or 17 installation.
           |
           |""".stripMargin
    val scalaVersions: String = supportedVersionsString(
      Snapshot.latest(useSnapshot = false, "2.13").version,
      5.minutes,
    ).getOrElse("No versions found")
    s"""
       |## Requirements$javaRequirement
       |
       |**macOS, Linux or Windows**. Metals is developed on many operating systems and
       |every PR is tested on Ubuntu, Windows and MacOS.
       |
       |**Scala 2.13, 2.12, 2.11 and Scala 3**. Metals supports these Scala versions:
       |
       |$scalaVersions
       |
       |Note that 2.11.x support is deprecated and it will be removed in future releases.
       |It's recommended to upgrade to Scala 2.12 or Scala 2.13
       |""".stripMargin
  }
}
