package tests.mcp

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.mcp.Claude
import scala.meta.internal.metals.mcp.Client
import scala.meta.internal.metals.mcp.CursorEditor
import scala.meta.internal.metals.mcp.McpConfig
import scala.meta.internal.metals.mcp.VSCodeEditor
import scala.meta.io.AbsolutePath

import munit.TestOptions
import tests.BaseSuite

class McpConfigSuite extends BaseSuite {
  def check(
      name: TestOptions,
      inputConfig: String,
      port: Int,
      projectName: String,
      expected: String,
      client: Client = CursorEditor,
  ): Unit = {
    test(name) {
      val obtained =
        McpConfig.createConfig(
          inputConfig,
          port,
          client.serverEntry.getOrElse(projectName + "-metals"),
          client,
        )
      assertNoDiff(obtained, expected)
    }
  }

  check(
    "new-config",
    "{ }",
    1234,
    "test-project",
    """{
      |  "mcpServers": {
      |    "test-project-metals": {
      |      "url": "http://localhost:1234/sse"
      |    }
      |  }
      |}""".stripMargin,
  )

  check(
    "new-config-vscode",
    "{ }",
    1234,
    "test-project",
    """{
      |  "servers": {
      |    "test-project-metals": {
      |      "url": "http://localhost:1234/sse",
      |      "type": "sse"
      |    }
      |  }
      |}""".stripMargin,
    client = VSCodeEditor,
  )

  check(
    "new-config",
    "{ }",
    1234,
    "test-project",
    """{
      |  "mcpServers": {
      |    "test-project-metals": {
      |      "url": "http://localhost:1234/sse"
      |    }
      |  }
      |}""".stripMargin,
  )

  check(
    "new-config-claude",
    "{ }",
    1234,
    "test-project",
    """{
      |  "mcpServers": {
      |    "metals": {
      |      "url": "http://localhost:1234/sse",
      |      "type": "sse"
      |    }
      |  }
      |}""".stripMargin,
    client = Claude,
  )

  check(
    "update-existing",
    """{
      |  "mcpServers": {
      |    "other-project-metals": {
      |      "url": "http://localhost:5678/sse"
      |    }
      |  }
      |}""".stripMargin,
    1234,
    "test-project",
    """{
      |  "mcpServers": {
      |    "other-project-metals": {
      |      "url": "http://localhost:5678/sse"
      |    },
      |    "test-project-metals": {
      |      "url": "http://localhost:1234/sse"
      |    }
      |  }
      |}""".stripMargin,
  )

  check(
    "update-existing-same-project",
    """{
      |  "mcpServers": {
      |    "test-project-metals": {
      |      "url": "http://localhost:5678/sse"
      |    }
      |  }
      |}""".stripMargin,
    1234,
    "test-project",
    """{
      |  "mcpServers": {
      |    "test-project-metals": {
      |      "url": "http://localhost:1234/sse"
      |    }
      |  }
      |}""".stripMargin,
  )

  test("generate-config-file") {
    val workspace = Files.createTempDirectory("metals-mcp-test")
    val projectPath = AbsolutePath(workspace)
    val port = 1234
    val projectName = "test-project"

    // First generation
    McpConfig.writeConfig(port, projectName, projectPath)
    val configFile = projectPath.resolve(".cursor/mcp.json")
    assert(configFile.exists)
    val firstContent = new String(
      Files.readAllBytes(configFile.toNIO),
      StandardCharsets.UTF_8,
    )
    assertNoDiff(
      firstContent,
      """{
        |  "mcpServers": {
        |    "test-project-metals": {
        |      "url": "http://localhost:1234/sse"
        |    }
        |  }
        |}""".stripMargin,
    )

    // Update with different port
    McpConfig.writeConfig(5678, projectName, projectPath)
    val secondContent = new String(
      Files.readAllBytes(configFile.toNIO),
      StandardCharsets.UTF_8,
    )
    assertNoDiff(
      secondContent,
      """{
        |  "mcpServers": {
        |    "test-project-metals": {
        |      "url": "http://localhost:5678/sse"
        |    }
        |  }
        |}""".stripMargin,
    )
  }

  test("getPort - valid config with port") {
    val config = """{
      "mcpServers": {
        "test-project-metals": {
          "url": "http://localhost:8080/sse"
        }
      }
    }"""

    assertEquals(
      McpConfig.getPort(config, "test-project"),
      Some(8080),
    )
  }

  test("getPort - missing mcpServers") {
    val config = "{}"
    assertEquals(
      McpConfig.getPort(config, "test-project"),
      None,
    )
  }

  test("getPort - missing project config") {
    val config = """{
      "mcpServers": {
        "other-project-metals": {
          "url": "http://localhost:8080/sse"
        }
      }
    }"""

    assertEquals(
      McpConfig.getPort(config, "test-project"),
      None,
    )
  }

  test("getPort - invalid url format") {
    val config = """{
      "mcpServers": {
        "test-project-metals": {
          "url": "invalid-url"
        }
      }
    }"""

    assertEquals(
      McpConfig.getPort(config, "test-project"),
      None,
    )
  }

  test("getPort - invalid JSON") {
    val config = "invalid json"
    assertEquals(
      McpConfig.getPort(config, "test-project"),
      None,
    )
  }
}
