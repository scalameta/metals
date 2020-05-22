package docs

import scala.meta.inputs.Input
import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.semver.SemVer.Version

import mdoc.Reporter
import mdoc.StringModifier

class VersionedDocsModifier extends StringModifier {
  val name = "since"

  override def process(
      version: String,
      code: Input,
      reporter: Reporter
  ): String = {
    val unreleased =
      Version.fromString(version) >= Version.fromString(BuildInfo.metalsVersion)

    if (unreleased) {
      s"> 🚧 This documentation section describes a feature that is currently unreleased. The expected release version is $version\n"
    } else {
      ""
    }

  }
}
