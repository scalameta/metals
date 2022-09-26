package tests.feature

import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.internal.semver.SemVer

class Worksheet211LspSuite extends tests.BaseWorksheetLspSuite(V.scala211)

class Worksheet3LspSuite extends tests.BaseWorksheetLspSuite(V.scala3) {
  override def versionSpecificCodeToValidate: String =
    """given str: String = """""
}

class LatestWorksheet3LspSuite
    extends tests.BaseWorksheetLspSuite(
      V.supportedScala3Versions
        .sortWith(SemVer.isCompatibleVersion)
        .reverse
        .head
    ) {
  override def versionSpecificCodeToValidate: String =
    """given str: String = """""
}

class Worksheet212LspSuite extends tests.BaseWorksheetLspSuite(V.scala212)
