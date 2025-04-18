package tests

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.mcp.CursorMcpConfig
import scala.meta.io.AbsolutePath

import munit.TestOptions

class McpConfigSuite extends BaseSuite {
  def check(
      name: TestOptions,
      inputConfig: String,
      port: Int,
      projectName: String,
      expected: String,
  ): Unit = {
    test(name) {
      val obtained = CursorMcpConfig.writeConfig(inputConfig, port, projectName)
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
    CursorMcpConfig.writeConfig(port, projectName, projectPath)
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
    CursorMcpConfig.writeConfig(5678, projectName, projectPath)
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
      CursorMcpConfig.getPort(config, "test-project"),
      Some(8080),
    )
  }

  test("getPort - missing mcpServers") {
    val config = "{}"
    assertEquals(
      CursorMcpConfig.getPort(config, "test-project"),
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
      CursorMcpConfig.getPort(config, "test-project"),
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
      CursorMcpConfig.getPort(config, "test-project"),
      None,
    )
  }

  test("getPort - invalid JSON") {
    val config = "invalid json"
    assertEquals(
      CursorMcpConfig.getPort(config, "test-project"),
      None,
    )
  }
}
