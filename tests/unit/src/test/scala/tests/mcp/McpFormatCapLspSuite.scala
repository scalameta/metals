package tests.mcp

import scala.meta.internal.metals.MetalsServerConfig

import tests.BaseLspSuite

class McpFormatCapLspSuite
    extends BaseLspSuite("mcp-format-cap")
    with McpTestUtils {

  override def serverConfig: MetalsServerConfig =
    super.serverConfig.copy(maxMcpSearchResults = 3)

  test("listed-entries-follow-configured-cap") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/metals.json
            |{"a": {}}
            |""".stripMargin
      )
      client <- startMcpServer()
      invalid = (1 to 4).toList.map(index => s"missing/Missing$index.scala")
      result <- client.formatFiles(invalid)
      expected =
        (List(
          "Format summary: formatted 0, unchanged 0, excluded 0, errors 4.",
          "",
          "Errors:",
        ) ++ invalid
          .take(3)
          .map(path => s"- $path: File not found or not a Scala file") ++ List(
          "- ... and 1 more"
        )).mkString("\n")
      _ = assertNoDiff(result, expected)
      _ <- client.shutdown()
    } yield ()
  }
}
