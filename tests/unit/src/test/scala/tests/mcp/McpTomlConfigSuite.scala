package tests.mcp

import scala.meta.internal.metals.mcp.McpTomlConfig

import munit.TestOptions
import tests.BaseSuite

class McpTomlConfigSuite extends BaseSuite {
  def checkUpsert(
      name: TestOptions,
      inputConfig: String,
      port: Int,
      expected: String,
  ): Unit = {
    test(name) {
      val obtained = McpTomlConfig.upsertServer(
        input = inputConfig,
        tableName = "mcp_servers",
        serverName = "metals",
        url = s"http://localhost:$port/mcp",
      )

      assertNoDiff(obtained, expected)
    }
  }

  checkUpsert(
    name = "upsert-server-new",
    inputConfig = "",
    port = 1234,
    expected = """[mcp_servers.metals]
                 |url = "http://localhost:1234/mcp"""".stripMargin,
  )

  checkUpsert(
    name = "upsert-server-existing",
    inputConfig = """model = "gpt-5"
                    |
                    |[mcp_servers.metals]
                    |url = "http://localhost:1234/mcp"
                    |
                    |[mcp_servers.other]
                    |url = "http://localhost:9999/mcp"""".stripMargin,
    port = 5678,
    expected = """model = "gpt-5"
                 |
                 |[mcp_servers.metals]
                 |url = "http://localhost:5678/mcp"
                 |
                 |[mcp_servers.other]
                 |url = "http://localhost:9999/mcp"""".stripMargin,
  )

  def checkRemove(
      name: TestOptions,
      inputConfig: String,
      expected: String,
  ): Unit = {
    test(name) {
      val obtained = McpTomlConfig.removeServer(
        input = inputConfig,
        tableName = "mcp_servers",
        serverName = "metals",
      )

      assertNoDiff(obtained, expected)
    }
  }

  checkRemove(
    name = "remove-server",
    inputConfig = """model = "gpt-5"
                    |
                    |[mcp_servers.metals]
                    |url = "http://localhost:1234/mcp"
                    |
                    |[mcp_servers.other]
                    |url = "http://localhost:9999/mcp"""".stripMargin,
    expected = """model = "gpt-5"
                 |
                 |[mcp_servers.other]
                 |url = "http://localhost:9999/mcp"""".stripMargin,
  )

  test("read-port") {
    val input =
      """[mcp_servers.metals]
        |url="http://localhost:8080/mcp?key=value"""".stripMargin

    assertEquals(
      McpTomlConfig.readPort(input, "mcp_servers", "metals"),
      Some(8080),
    )
  }

  test("read-port-single-quoted-url") {
    val input =
      """[mcp_servers.metals]
        |url='http://localhost:8080/mcp'""".stripMargin

    assertEquals(
      McpTomlConfig.readPort(input, "mcp_servers", "metals"),
      Some(8080),
    )
  }

}
