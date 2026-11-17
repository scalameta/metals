package tests.mcp

import scala.meta.internal.metals.mcp.McpDependencyFinder

import munit.FunSuite

class McpDependencyFinderSuite extends FunSuite {

  test("name-and-version") {
    assertEquals(
      McpDependencyFinder.candidates(
        organization = Some("org.scalaxb"),
        name = Some("sbt-scalaxb"),
        version = Some("1.12"),
        scalaBinaryVersion = "2.13",
        sbtVersion = Some("1.11.7"),
      ),
      List(
        "version" -> "org.scalaxb:sbt-scalaxb_2.13:1.12",
        "version" -> "org.scalaxb:sbt-scalaxb_2.12_1.0:1.12",
        "version" -> "org.scalaxb:sbt-scalaxb_sbt2_3:1.12",
        "version" -> "org.scalaxb:sbt-scalaxb:1.12",
      ),
    )
  }

  test("sbt2-build") {
    assertEquals(
      McpDependencyFinder.candidates(
        organization = Some("org.scalaxb"),
        name = Some("sbt-scalaxb"),
        version = Some("1.12"),
        scalaBinaryVersion = "3",
        sbtVersion = Some("2.0.0"),
      ),
      List(
        "version" -> "org.scalaxb:sbt-scalaxb_3:1.12",
        "version" -> "org.scalaxb:sbt-scalaxb_sbt2_3:1.12",
        "version" -> "org.scalaxb:sbt-scalaxb_2.12_1.0:1.12",
        "version" -> "org.scalaxb:sbt-scalaxb:1.12",
      ),
    )
  }

  test("latest-version") {
    assertEquals(
      McpDependencyFinder.candidates(
        organization = Some("org.typelevel"),
        name = Some("cats-core"),
        version = Some("latest"),
        scalaBinaryVersion = "2.13",
        sbtVersion = None,
      ),
      List(
        "version" -> "org.typelevel:cats-core_2.13:",
        "version" -> "org.typelevel:cats-core_2.12_1.0:",
        "version" -> "org.typelevel:cats-core_sbt2_3:",
        "version" -> "org.typelevel:cats-core:",
      ),
    )
  }

  test("name-only") {
    assertEquals(
      McpDependencyFinder.candidates(
        organization = Some("org.typelevel"),
        name = Some("cats-co"),
        version = None,
        scalaBinaryVersion = "2.13",
        sbtVersion = None,
      ),
      List(
        "version" -> "org.typelevel:cats-co_2.13:",
        "version" -> "org.typelevel:cats-co_2.12_1.0:",
        "version" -> "org.typelevel:cats-co_sbt2_3:",
        "version" -> "org.typelevel:cats-co:",
        "name" -> "org.typelevel:cats-co",
      ),
    )
  }

  test("organization-only") {
    assertEquals(
      McpDependencyFinder.candidates(
        organization = Some("org.typel"),
        name = None,
        version = None,
        scalaBinaryVersion = "2.13",
        sbtVersion = None,
      ),
      List(
        "name" -> "org.typel:",
        "organization" -> "org.typel",
      ),
    )
  }

  test("no-organization") {
    assertEquals(
      McpDependencyFinder.candidates(
        organization = None,
        name = Some("cats-core"),
        version = Some("2.10"),
        scalaBinaryVersion = "2.13",
        sbtVersion = None,
      ),
      Nil,
    )
  }
}
