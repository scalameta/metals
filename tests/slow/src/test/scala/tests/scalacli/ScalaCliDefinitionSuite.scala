package tests.scalacli

import scala.meta.internal.metals.{BuildInfo => V}

class ScalaCliDefinitionSuite extends BaseScalaCliSuite(V.latestScala3Next) {
  test("script-definition") {
    val testCase =
      s"""|//> using scala $scalaVersion
          |
          |val list = List(1, 2, 3, 4)
          |val x = fiv@@e()
          |
          |def five() = 5
          |""".stripMargin

    for {
      _ <- scalaCliInitialize(useBsp = true)(
        s"""|/Script.sc
            |${testCase.replace("@@", "")}
            |""".stripMargin
      )
      _ <- server.didOpen("Script.sc")
      _ <- waitForImport(useBsp = true)
      locations <- server.definition("Script.sc", testCase, workspace)
      _ = assert(locations.nonEmpty, "Expected to find definition location")
      _ = assert(
        locations.forall(_.getUri().endsWith("Script.sc")),
        s"Expected URI to end with 'Script.sc' but got: ${locations
            .map(_.getUri())
            .mkString(", ")}",
      )
      _ = assert(
        !locations.exists(_.getUri().contains(".sc.scala")),
        s"URI should not contain '.sc.scala' (wrapped file): ${locations
            .map(_.getUri())
            .mkString(", ")}",
      )
      // Definition should point to line 5 (0-indexed, adjusted for using directive) where 'def five()' is defined
      _ = assertEquals(
        locations.map(_.getRange().getStart().getLine()),
        List(5),
      )
    } yield ()
  }
}
