package tests.feature

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.internal.semver.SemVer

import coursierapi.Complete

class Worksheet211LspSuite extends tests.BaseWorksheetLspSuite(V.scala211)

class Worksheet3LspSuite extends tests.BaseWorksheetLspSuite(V.scala3) {
  override def versionSpecificCodeToValidate: String =
    """given str: String = """""

  override def versionSpecificScalacOptionsToValidate: List[String] = List(
    "-Ycheck-reentrant"
  )
}

class Worksheet3NextSuite
    extends tests.BaseWorksheetLspSuite(Worksheet3NextSuite.scala3Next) {
  override def versionSpecificCodeToValidate: String =
    """given str: String = """""
}

object Worksheet3NextSuite {
  def scala3Next: String =
    Complete
      .create()
      .withInput("org.scala-lang:scala3-compiler_3:")
      .complete()
      .getCompletions()
      .asScala
      .toList
      .reverse
      .collectFirst { version =>
        SemVer.Version.fromString(version) match {
          case SemVer.Version(_, _, _, None, None, None) => version
        }
      }
      .get
}

class Worksheet212LspSuite extends tests.BaseWorksheetLspSuite(V.scala212)
