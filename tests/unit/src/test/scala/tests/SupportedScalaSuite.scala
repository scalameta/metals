package tests

import scala.concurrent.duration._

import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.metals.Main

class SupportedScalaSuite extends BaseSuite {

  test("released-version") {
    Main.supportedVersionsString("1.2.0", 5.minutes) match {
      case Left(value) =>
        assert(
          value.contains(V.supportedScalaVersions.head),
          "Expected any versions if failed to fetch from sonatype",
        )
      case Right(supported) =>
        assertNoDiff(
          supported,
          """|- Scala 2.11:
             |   2.11.12
             |
             | - Scala 2.12:
             |   2.12.11, 2.12.12, 2.12.13, 2.12.14, 2.12.15, 2.12.16, 2.12.17, 2.12.18, 2.12.19
             |
             | - Scala 2.13:
             |   2.13.5, 2.13.6, 2.13.7, 2.13.8, 2.13.9, 2.13.10, 2.13.11, 2.13.12, 2.13.13
             |
             | - Scala 3:
             |   3.1.0, 3.2.0, 3.3.0, 3.1.1, 3.2.1, 3.3.1, 3.1.2, 3.2.2, 3.3.2-RC1, 3.3.2-RC3, 3.1.3
             |
             |
             |Scala 3 versions from 3.3.4 are automatically supported by Metals.
             |
             |Any older Scala versions will no longer get bugfixes, but should still
             |work properly with newest Metals.
             |""".stripMargin,
        )
    }
  }

  test("snapshot-version") {

    Main.supportedVersionsString(
      "1.5.3+42-a4e9168d-SNAPSHOT",
      5.minutes,
    ) match {
      case Left(value) =>
        assert(
          value.contains(V.supportedScalaVersions.head),
          "Expected any versions if failed to fetch from sonatype",
        )
      case Right(supported) =>
        assertNoDiff(
          supported,
          """|- Scala 2.11:
             |   2.11.12
             |
             | - Scala 2.12:
             |   2.12.17, 2.12.18, 2.12.19, 2.12.20
             |
             | - Scala 2.13:
             |   2.13.13, 2.13.14, 2.13.15, 2.13.16
             |
             |
             |Scala 3 versions from 3.3.4 are automatically supported by Metals.
             |
             |Any older Scala versions will no longer get bugfixes, but should still
             |work properly with newest Metals. 
             |""".stripMargin,
        )
    }
  }
}
