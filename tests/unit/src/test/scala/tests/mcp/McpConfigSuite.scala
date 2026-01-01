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
      |      "type": "http"
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
      |      "type": "http"
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

  test("getPort - Claude client uses serverEntry") {
    val config = """{
      "mcpServers": {
        "metals": {
          "url": "http://localhost:8080/sse"
        }
      }
    }"""

    assertEquals(
      McpConfig.getPort(config, "test-project", Claude),
      Some(8080),
    )
  }

  test("deleteConfig - preserves other entries") {
    val workspace = Files.createTempDirectory("metals-mcp-test")
    val projectPath = AbsolutePath(workspace)
    val configFile = projectPath.resolve(".cursor/mcp.json")

    // Create config with multiple entries
    val initialConfig = """{
                          |  "mcpServers": {
                          |    "test-project-metals": {
                          |      "url": "http://localhost:1234/sse"
                          |    },
                          |    "other-project-metals": {
                          |      "url": "http://localhost:5678/sse"
                          |    }
                          |  }
                          |}""".stripMargin

    Files.createDirectories(configFile.parent.toNIO)
    Files.write(
      configFile.toNIO,
      initialConfig.getBytes(StandardCharsets.UTF_8),
    )

    // Delete only the test-project entry
    McpConfig.deleteConfig(projectPath, "test-project", CursorEditor)

    // Config should still exist with other entry
    assert(configFile.exists)
    val remainingContent = new String(
      Files.readAllBytes(configFile.toNIO),
      StandardCharsets.UTF_8,
    )
    assertNoDiff(
      remainingContent,
      """{
        |  "mcpServers": {
        |    "other-project-metals": {
        |      "url": "http://localhost:5678/sse"
        |    }
        |  }
        |}""".stripMargin,
    )
  }

  test("deleteConfig - deletes file when only entry") {
    val workspace = Files.createTempDirectory("metals-mcp-test")
    val projectPath = AbsolutePath(workspace)
    val configFile = projectPath.resolve(".cursor/mcp.json")

    // Create config with only one entry
    val initialConfig = """{
                          |  "mcpServers": {
                          |    "test-project-metals": {
                          |      "url": "http://localhost:1234/sse"
                          |    }
                          |  }
                          |}""".stripMargin

    Files.createDirectories(configFile.parent.toNIO)
    Files.write(
      configFile.toNIO,
      initialConfig.getBytes(StandardCharsets.UTF_8),
    )

    // Delete the only entry
    McpConfig.deleteConfig(projectPath, "test-project", CursorEditor)

    // Config file should be deleted
    assert(!configFile.exists)
  }

  test("deleteConfig - handles missing entry") {
    val workspace = Files.createTempDirectory("metals-mcp-test")
    val projectPath = AbsolutePath(workspace)
    val configFile = projectPath.resolve(".cursor/mcp.json")

    // Create config without the target entry
    val initialConfig = """{
                          |  "mcpServers": {
                          |    "other-project-metals": {
                          |      "url": "http://localhost:5678/sse"
                          |    }
                          |  }
                          |}""".stripMargin

    Files.createDirectories(configFile.parent.toNIO)
    Files.write(
      configFile.toNIO,
      initialConfig.getBytes(StandardCharsets.UTF_8),
    )

    // Try to delete non-existent entry
    McpConfig.deleteConfig(projectPath, "test-project", CursorEditor)

    // Config should remain unchanged
    assert(configFile.exists)
    val remainingContent = new String(
      Files.readAllBytes(configFile.toNIO),
      StandardCharsets.UTF_8,
    )
    assertNoDiff(
      remainingContent,
      initialConfig,
    )
  }

  test("deleteConfig - handles missing file") {
    val workspace = Files.createTempDirectory("metals-mcp-test")
    val projectPath = AbsolutePath(workspace)
    val configFile = projectPath.resolve(".cursor/mcp.json")

    // Ensure file doesn't exist
    assert(!configFile.exists)

    // Try to delete from non-existent file (should not error)
    McpConfig.deleteConfig(projectPath, "test-project", CursorEditor)

    // File should still not exist
    assert(!configFile.exists)
  }

  test("deleteConfig - handles invalid JSON") {
    val workspace = Files.createTempDirectory("metals-mcp-test")
    val projectPath = AbsolutePath(workspace)
    val configFile = projectPath.resolve(".cursor/mcp.json")

    // Create config with invalid JSON
    val invalidConfig = "{ invalid json"
    Files.createDirectories(configFile.parent.toNIO)
    Files.write(
      configFile.toNIO,
      invalidConfig.getBytes(StandardCharsets.UTF_8),
    )

    // Try to delete from invalid JSON (should not error)
    McpConfig.deleteConfig(projectPath, "test-project", CursorEditor)

    // Config should remain unchanged
    assert(configFile.exists)
    val remainingContent = new String(
      Files.readAllBytes(configFile.toNIO),
      StandardCharsets.UTF_8,
    )
    assertEquals(remainingContent, invalidConfig)
  }
}
