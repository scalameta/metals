package docs

import scala.meta.inputs.Input
import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.ScalaVersions
import scala.meta.internal.semver.SemVer

import mdoc.Reporter
import mdoc.StringModifier

class RequirementsModifier extends StringModifier {
  override val name: String = "requirements"

  def supportedScalaVersions: String = {
    val grouped = BuildInfo.supportedScalaVersions.groupBy { version =>
      if (ScalaVersions.isScala3Version(version)) "3"
      else ScalaVersions.scalaBinaryVersionFromFullVersion(version)
    }

    grouped
      .map { case (binary, versions) =>
        val versionString =
          versions.sortWith(SemVer.isLaterVersion).reverse.mkString(", ")
        s"""| - **Scala $binary**:
            |   $versionString""".stripMargin
      }
      .mkString("\n\n")
  }

  override def process(
      info: String,
      code: Input,
      reporter: Reporter
  ): String = {
    s"""
       |## Requirements
       |
       |**Java 8, 11, 17 provided by OpenJDK or Oracle**. Eclipse OpenJ9 is not
       |supported, please make sure the `JAVA_HOME` environment variable
       |points to a valid Java 8, 11 or 17 installation.
       |
       |**macOS, Linux or Windows**. Metals is developed on many operating systems and 
       |every PR is tested on Ubuntu, Windows and MacOS.
       |
       |**Scala 2.13, 2.12, 2.11 and Scala 3**. Metals supports these Scala versions:
       |
       |$supportedScalaVersions
       |
       |Note that 2.11.x support is deprecated and it will be removed in future releases.
       |It's recommended to upgrade to Scala 2.12 or Scala 2.13
       |""".stripMargin
  }
}
