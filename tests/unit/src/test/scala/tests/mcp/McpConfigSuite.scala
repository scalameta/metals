package tests.mcp

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.mcp.Claude
import scala.meta.internal.metals.mcp.Client
import scala.meta.internal.metals.mcp.CursorEditor
import scala.meta.internal.metals.mcp.McpConfig
import scala.meta.internal.metals.mcp.OpenCode
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
      |      "url": "http://localhost:1234/mcp"
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
      |      "url": "http://localhost:1234/mcp",
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
      |      "url": "http://localhost:1234/mcp"
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
      |      "url": "http://localhost:1234/mcp",
      |      "type": "http"
      |    }
      |  }
      |}""".stripMargin,
    client = Claude,
  )

  check(
    "new-config-opencode",
    "{ }",
    1234,
    "test-project",
    """{
      |  "$schema": "https://opencode.ai/config.json",
      |  "mcp": {
      |    "metals-lsp": {
      |      "url": "http://localhost:1234/mcp",
      |      "type": "remote",
      |      "enabled": true
      |    }
      |  }
      |}""".stripMargin,
    client = OpenCode,
  )

  check(
    "update-existing",
    """{
      |  "mcpServers": {
      |    "other-project-metals": {
      |      "url": "http://localhost:5678/mcp"
      |    }
      |  }
      |}""".stripMargin,
    1234,
    "test-project",
    """{
      |  "mcpServers": {
      |    "other-project-metals": {
      |      "url": "http://localhost:5678/mcp"
      |    },
      |    "test-project-metals": {
      |      "url": "http://localhost:1234/mcp"
      |    }
      |  }
      |}""".stripMargin,
  )

  check(
    "update-existing-same-project",
    """{
      |  "mcpServers": {
      |    "test-project-metals": {
      |      "url": "http://localhost:5678/mcp"
      |    }
      |  }
      |}""".stripMargin,
    1234,
    "test-project",
    """{
      |  "mcpServers": {
      |    "test-project-metals": {
      |      "url": "http://localhost:1234/mcp"
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
    McpConfig.writeConfig(
      port,
      projectName,
      projectPath,
      activeClientExtensionIds = Set.empty,
    )
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
        |      "url": "http://localhost:1234/mcp"
        |    }
        |  }
        |}""".stripMargin,
    )

    // Update with different port
    McpConfig.writeConfig(
      5678,
      projectName,
      projectPath,
      activeClientExtensionIds = Set.empty,
    )
    val secondContent = new String(
      Files.readAllBytes(configFile.toNIO),
      StandardCharsets.UTF_8,
    )
    assertNoDiff(
      secondContent,
      """{
        |  "mcpServers": {
        |    "test-project-metals": {
        |      "url": "http://localhost:5678/mcp"
        |    }
        |  }
        |}""".stripMargin,
    )
  }

  test("opencode-generate-config-file") {
    val workspace = Files.createTempDirectory("metals-mcp-test")
    val projectPath = AbsolutePath(workspace)
    val port = 1234
    val projectName = "test-project"

    // First generation
    McpConfig.writeConfig(
      port,
      projectName,
      projectPath,
      client = OpenCode,
      activeClientExtensionIds = Set.empty,
    )
    val configFile = projectPath.resolve("opencode.jsonc")
    assert(configFile.exists)
    val firstContent = new String(
      Files.readAllBytes(configFile.toNIO),
      StandardCharsets.UTF_8,
    )
    assertNoDiff(
      firstContent,
      """{
        |  "$schema": "https://opencode.ai/config.json",
        |  "mcp": {
        |    "metals-lsp": {
        |      "url": "http://localhost:1234/mcp",
        |      "type": "remote",
        |      "enabled": true
        |    }
        |  }
        |}""".stripMargin,
    )

    // Update with different port
    McpConfig.writeConfig(
      5678,
      projectName,
      projectPath,
      client = OpenCode,
      activeClientExtensionIds = Set.empty,
    )
    val secondContent = new String(
      Files.readAllBytes(configFile.toNIO),
      StandardCharsets.UTF_8,
    )
    assertNoDiff(
      secondContent,
      """{
        |  "$schema": "https://opencode.ai/config.json",
        |  "mcp": {
        |    "metals-lsp": {
        |      "url": "http://localhost:5678/mcp",
        |      "type": "remote",
        |      "enabled": true
        |    }
        |  }
        |}""".stripMargin,
    )
  }
  test("vscode-and-kilocode-generate-config-file") {
    val workspace = Files.createTempDirectory("metals-mcp-test")
    val projectPath = AbsolutePath(workspace)
    val port = 1234
    val projectName = "test-project"
    val activeClientExtensionIds = Set("kilocode.kilo-code")
    // First generation
    McpConfig.writeConfig(
      port,
      projectName,
      projectPath,
      client = VSCodeEditor,
      activeClientExtensionIds = activeClientExtensionIds,
    )
    val vscodeConfigFile = projectPath.resolve(".vscode/mcp.json")
    val kiloConfigFile = projectPath.resolve(".kilo/kilo.json")
    assert(vscodeConfigFile.exists, "VSCode config file should exist")
    assert(kiloConfigFile.exists, "KiloCode extension config file should exist")
    val vscodeFirstContent = vscodeConfigFile.readText
    assertNoDiff(
      vscodeFirstContent,
      """{
        |  "servers": {
        |    "test-project-metals": {
        |      "url": "http://localhost:1234/mcp",
        |      "type": "http"
        |    }
        |  }
        |}""".stripMargin,
    )
    val kiloContent = kiloConfigFile.readText
    assertNoDiff(
      kiloContent,
      """{
        |  "$schema": "https://app.kilo.ai/config.json",
        |  "mcp": {
        |    "test-project-metals": {
        |      "url": "http://localhost:1234/mcp",
        |      "type": "remote"
        |    }
        |  }
        |}""".stripMargin,
    )

    // Update with different port
    McpConfig.writeConfig(
      5678,
      projectName,
      projectPath,
      client = VSCodeEditor,
      activeClientExtensionIds = activeClientExtensionIds,
    )
    val vscodeSecondContent = vscodeConfigFile.readText
    assertNoDiff(
      vscodeSecondContent,
      """{
        |  "servers": {
        |    "test-project-metals": {
        |      "url": "http://localhost:5678/mcp",
        |      "type": "http"
        |    }
        |  }
        |}""".stripMargin,
    )
    val kiloSecondContent = kiloConfigFile.readText
    assertNoDiff(
      kiloSecondContent,
      """{
        |  "$schema": "https://app.kilo.ai/config.json",
        |  "mcp": {
        |    "test-project-metals": {
        |      "url": "http://localhost:5678/mcp",
        |      "type": "remote"
        |    }
        |  }
        |}""".stripMargin,
    )
  }

  def testWithoutKiloExtension(activeClientExtensionIds: Set[String]): Unit = {
    val workspace = Files.createTempDirectory("metals-mcp-test")
    val projectPath = AbsolutePath(workspace)
    val port = 1234
    val projectName = "test-project"
    // First generation
    McpConfig.writeConfig(
      port,
      projectName,
      projectPath,
      client = VSCodeEditor,
      activeClientExtensionIds = activeClientExtensionIds,
    )
    val vscodeConfigFile = projectPath.resolve(".vscode/mcp.json")
    val kiloConfigFile = projectPath.resolve(".kilocode/mcp.json")
    assert(vscodeConfigFile.exists, "VSCode config file should exist")
    assert(!kiloConfigFile.exists, "KiloCode config file should not exist")
    val vscodeFirstContent = vscodeConfigFile.readText
    assertNoDiff(
      vscodeFirstContent,
      """{
        |  "servers": {
        |    "test-project-metals": {
        |      "url": "http://localhost:1234/mcp",
        |      "type": "http"
        |    }
        |  }
        |}""".stripMargin,
    )
  }

  test("vscode-and-no-extension-generate-config-file") {
    testWithoutKiloExtension(Set.empty)
  }

  test("vscode-and-unlisted-extension-generate-config-file") {
    testWithoutKiloExtension(Set("some.other.extension"))
  }

  test("getPort - valid config with port") {
    val config = """{
      "mcpServers": {
        "test-project-metals": {
          "url": "http://localhost:8080/mcp"
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
          "url": "http://localhost:8080/mcp"
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
          "url": "http://localhost:8080/mcp"
        }
      }
    }"""

    assertEquals(
      McpConfig.getPort(config, "test-project", Claude),
      Some(8080),
    )
  }

  test("getPort - OpenCode client uses serverEntry") {
    val config = """{
      "$schema": "https://opencode.ai/config.json",
      "mcp": {
        "metals-lsp": {
          "url": "http://localhost:8080/mcp"
        }
      }
    }"""

    assertEquals(
      McpConfig.getPort(config, "test-project", OpenCode),
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
                          |      "url": "http://localhost:1234/mcp"
                          |    },
                          |    "other-project-metals": {
                          |      "url": "http://localhost:5678/mcp"
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
        |      "url": "http://localhost:5678/mcp"
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
                          |      "url": "http://localhost:1234/mcp"
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
                          |      "url": "http://localhost:5678/mcp"
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

  test("rewrites-sse-to-mcp") {
    val config = """{
      "mcpServers": {
        "test-project-metals": {
          "url": "http://localhost:8080/sse"
        }
      }
    }"""

    val result =
      McpConfig.rewriteOldEndpoint(config, "test-project", CursorEditor, 8080)
    assert(result.isDefined)
    assertNoDiff(
      result.get,
      """{
        |  "mcpServers": {
        |    "test-project-metals": {
        |      "url": "http://localhost:8080/mcp"
        |    }
        |  }
        |}""".stripMargin,
    )
  }

  test("rewrites-sse-to-mcp-additional-properties") {
    val config = """{
      "servers": {
        "test-project-metals": {
          "url": "http://localhost:8080/sse",
          "type": "sse"
        }
      }
    }"""

    val result =
      McpConfig.rewriteOldEndpoint(config, "test-project", VSCodeEditor, 8080)
    assert(result.isDefined)
    assertNoDiff(
      result.get,
      """{
        |  "servers": {
        |    "test-project-metals": {
        |      "url": "http://localhost:8080/mcp",
        |      "type": "http"
        |    }
        |  }
        |}""".stripMargin,
    )
  }

  test("does-not-rewrite-already-mcp") {
    val config = """{
      "mcpServers": {
        "test-project-metals": {
          "url": "http://localhost:8080/mcp"
        }
      }
    }"""

    val result =
      McpConfig.rewriteOldEndpoint(config, "test-project", CursorEditor, 8080)
    assertEquals(result, None)
  }

  test("no-rewrite-no-project") {
    val config = """{
      "mcpServers": {
        "other-project-metals": {
          "url": "http://localhost:8080/sse"
        }
      }
    }"""

    val result =
      McpConfig.rewriteOldEndpoint(config, "test-project", CursorEditor, 8080)
    assertEquals(result, None)
  }

  test("rewrites-file") {
    val workspace = Files.createTempDirectory("metals-mcp-test")
    val projectPath = AbsolutePath(workspace)
    val configFile = projectPath.resolve(".cursor/mcp.json")

    // Create config with old /sse endpoint
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

    // Rewrite the config
    McpConfig.rewriteOldEndpointIfNeeded(
      projectPath,
      "test-project",
      CursorEditor,
      1234,
    )

    val newContent = new String(
      Files.readAllBytes(configFile.toNIO),
      StandardCharsets.UTF_8,
    )
    assertNoDiff(
      newContent,
      """{
        |  "mcpServers": {
        |    "test-project-metals": {
        |      "url": "http://localhost:1234/mcp"
        |    }
        |  }
        |}""".stripMargin,
    )
  }

  test("no-rewrite-needed") {
    val workspace = Files.createTempDirectory("metals-mcp-test")
    val projectPath = AbsolutePath(workspace)
    val configFile = projectPath.resolve(".cursor/mcp.json")

    // Create config with new /mcp endpoint
    val initialConfig = """{
                          |  "mcpServers": {
                          |    "test-project-metals": {
                          |      "url": "http://localhost:1234/mcp"
                          |    }
                          |  }
                          |}""".stripMargin

    Files.createDirectories(configFile.parent.toNIO)
    Files.write(
      configFile.toNIO,
      initialConfig.getBytes(StandardCharsets.UTF_8),
    )

    // No rewrite needed
    McpConfig.rewriteOldEndpointIfNeeded(
      projectPath,
      "test-project",
      CursorEditor,
      1234,
    )

    val newContent = new String(
      Files.readAllBytes(configFile.toNIO),
      StandardCharsets.UTF_8,
    )
    assertNoDiff(
      newContent,
      """{
        |  "mcpServers": {
        |    "test-project-metals": {
        |      "url": "http://localhost:1234/mcp"
        |    }
        |  }
        |}""".stripMargin,
    )
  }
  test("opencode-merge-into-existing-opencode.json") {
    val workspace = Files.createTempDirectory("metals-mcp-test")
    val projectPath = AbsolutePath(workspace)
    val port = 1234
    val projectName = "test-project"

    // Create existing opencode.json with other content
    val existingConfig = """{
                           |  "version": "1.0",
                           |  "mcp": {
                           |    "other-server": {
                           |      "url": "http://example.com/mcp"
                           |    }
                           |  }
                           |}""".stripMargin
    val configFile = projectPath.resolve("opencode.json")
    Files.write(
      configFile.toNIO,
      existingConfig.getBytes(StandardCharsets.UTF_8),
    )

    // Write metals config - should merge into existing file
    McpConfig.writeConfig(
      port,
      projectName,
      projectPath,
      client = OpenCode,
      activeClientExtensionIds = Set.empty,
    )

    // Should use existing opencode.json (not create jsonc)
    assert(configFile.exists)
    assert(!projectPath.resolve("opencode.jsonc").exists)

    val content = new String(
      Files.readAllBytes(configFile.toNIO),
      StandardCharsets.UTF_8,
    )
    assertNoDiff(
      content,
      """{
        |  "version": "1.0",
        |  "mcp": {
        |    "other-server": {
        |      "url": "http://example.com/mcp"
        |    },
        |    "metals-lsp": {
        |      "url": "http://localhost:1234/mcp",
        |      "type": "remote",
        |      "enabled": true
        |    }
        |  },
        |  "$schema": "https://opencode.ai/config.json"
        |}""".stripMargin,
    )
  }

  test("opencode-merge-into-existing-opencode.jsonc") {
    val workspace = Files.createTempDirectory("metals-mcp-test")
    val projectPath = AbsolutePath(workspace)
    val port = 1234
    val projectName = "test-project"

    // Create existing opencode.jsonc with other content
    val existingConfig = """{
                           |  // This is a comment - JSONC supports comments
                           |  "mcp": {
                           |    "other-server": {
                           |      "url": "http://example.com/mcp"
                           |    }
                           |  }
                           |}""".stripMargin
    val configFile = projectPath.resolve("opencode.jsonc")
    Files.write(
      configFile.toNIO,
      existingConfig.getBytes(StandardCharsets.UTF_8),
    )

    // Write metals config - should merge into existing file
    McpConfig.writeConfig(
      port,
      projectName,
      projectPath,
      client = OpenCode,
      activeClientExtensionIds = Set.empty,
    )

    // Should use existing opencode.jsonc
    assert(configFile.exists)
    assert(!projectPath.resolve("opencode.json").exists)

    val content = new String(
      Files.readAllBytes(configFile.toNIO),
      StandardCharsets.UTF_8,
    )
    assertNoDiff(
      content,
      """{
        |  "mcp": {
        |    "other-server": {
        |      "url": "http://example.com/mcp"
        |    },
        |    "metals-lsp": {
        |      "url": "http://localhost:1234/mcp",
        |      "type": "remote",
        |      "enabled": true
        |    }
        |  },
        |  "$schema": "https://opencode.ai/config.json"
        |}""".stripMargin,
    )
  }

  test("opencode-prefer-json-over-jsonc") {
    val workspace = Files.createTempDirectory("metals-mcp-test")
    val projectPath = AbsolutePath(workspace)
    val port = 1234
    val projectName = "test-project"

    // Create both opencode.json and opencode.jsonc
    val jsonFile = projectPath.resolve("opencode.json")
    val jsoncFile = projectPath.resolve("opencode.jsonc")
    val jsonContent = """{ "version": "json" }"""
    val jsoncContent = """{ "version": "jsonc" }"""
    Files.write(jsonFile.toNIO, jsonContent.getBytes(StandardCharsets.UTF_8))
    Files.write(jsoncFile.toNIO, jsoncContent.getBytes(StandardCharsets.UTF_8))

    // Write metals config
    McpConfig.writeConfig(
      port,
      projectName,
      projectPath,
      client = OpenCode,
      activeClientExtensionIds = Set.empty,
    )

    // Should prefer opencode.json
    val jsonUpdated = new String(
      Files.readAllBytes(jsonFile.toNIO),
      StandardCharsets.UTF_8,
    )
    assert(jsonUpdated.contains("metals-lsp"))

    // opencode.jsonc should remain unchanged
    val jsoncUnchanged = new String(
      Files.readAllBytes(jsoncFile.toNIO),
      StandardCharsets.UTF_8,
    )
    assertEquals(jsoncUnchanged, jsoncContent)
  }

  test("opencode-deleteConfig-removes-metals-entry") {
    val workspace = Files.createTempDirectory("metals-mcp-test")
    val projectPath = AbsolutePath(workspace)
    val configFile = projectPath.resolve("opencode.json")

    // Create config with metals-lsp and other entries
    val initialConfig = """{
                          |  "mcp": {
                          |    "metals-lsp": {
                          |      "url": "http://localhost:1234/mcp",
                          |      "type": "remote",
                          |      "enabled": true
                          |    },
                          |    "other-server": {
                          |      "url": "http://example.com/mcp"
                          |    }
                          |  }
                          |}""".stripMargin
    Files.write(
      configFile.toNIO,
      initialConfig.getBytes(StandardCharsets.UTF_8),
    )

    // Delete metals entry
    McpConfig.deleteConfig(projectPath, "test-project", OpenCode)

    // Config should still exist with other entry
    assert(configFile.exists)
    val remainingContent = new String(
      Files.readAllBytes(configFile.toNIO),
      StandardCharsets.UTF_8,
    )
    assertNoDiff(
      remainingContent,
      """{
        |  "mcp": {
        |    "other-server": {
        |      "url": "http://example.com/mcp"
        |    }
        |  }
        |}""".stripMargin,
    )
  }

  test("opencode-deleteConfig-deletes-empty-file") {
    val workspace = Files.createTempDirectory("metals-mcp-test")
    val projectPath = AbsolutePath(workspace)
    val configFile = projectPath.resolve("opencode.json")

    // Create config with only metals-lsp entry
    val initialConfig = """{
                          |  "$schema": "https://opencode.ai/config.json",
                          |  "mcp": {
                          |    "metals-lsp": {
                          |      "url": "http://localhost:1234/mcp",
                          |      "type": "remote",
                          |      "enabled": true
                          |    }
                          |  }
                          |}""".stripMargin
    Files.write(
      configFile.toNIO,
      initialConfig.getBytes(StandardCharsets.UTF_8),
    )

    // Delete metals entry
    McpConfig.deleteConfig(projectPath, "test-project", OpenCode)

    // Config file should be deleted (only $schema and empty mcp remain)
    assert(!configFile.exists)
  }

  test("opencode-deleteConfig-preserves-file-with-user-content") {
    val workspace = Files.createTempDirectory("metals-mcp-test")
    val projectPath = AbsolutePath(workspace)
    val configFile = projectPath.resolve("opencode.json")

    // Create config with metals-lsp and user's own settings
    val initialConfig = """{
                          |  "$schema": "https://opencode.ai/config.json",
                          |  "customSetting": "user-value",
                          |  "mcp": {
                          |    "metals-lsp": {
                          |      "url": "http://localhost:1234/mcp",
                          |      "type": "remote",
                          |      "enabled": true
                          |    }
                          |  }
                          |}""".stripMargin
    Files.write(
      configFile.toNIO,
      initialConfig.getBytes(StandardCharsets.UTF_8),
    )

    // Delete metals entry
    McpConfig.deleteConfig(projectPath, "test-project", OpenCode)

    // Config should still exist with user content and $schema
    assert(configFile.exists)
    val remainingContent = new String(
      Files.readAllBytes(configFile.toNIO),
      StandardCharsets.UTF_8,
    )
    assert(remainingContent.contains("$schema"))
    assert(remainingContent.contains("customSetting"))
    assert(!remainingContent.contains("metals-lsp"))
  }

  test("opencode-readPort-from-json") {
    val workspace = Files.createTempDirectory("metals-mcp-test")
    val projectPath = AbsolutePath(workspace)
    val configFile = projectPath.resolve("opencode.json")

    val config = """{
                   |  "$schema": "https://opencode.ai/config.json",
                   |  "mcp": {
                   |    "metals-lsp": {
                   |      "url": "http://localhost:8080/mcp"
                   |    }
                   |  }
                   |}""".stripMargin
    Files.write(configFile.toNIO, config.getBytes(StandardCharsets.UTF_8))

    assertEquals(
      McpConfig.readPort(projectPath, "test-project", OpenCode),
      Some(8080),
    )
  }

  test("opencode-readPort-from-jsonc") {
    val workspace = Files.createTempDirectory("metals-mcp-test")
    val projectPath = AbsolutePath(workspace)
    val configFile = projectPath.resolve("opencode.jsonc")

    val config = """{
                   |  "mcp": {
                   |    "metals-lsp": {
                   |      "url": "http://localhost:9090/mcp"
                   |    }
                   |  }
                   |}""".stripMargin
    Files.write(configFile.toNIO, config.getBytes(StandardCharsets.UTF_8))

    assertEquals(
      McpConfig.readPort(projectPath, "test-project", OpenCode),
      Some(9090),
    )
  }

  test("opencode-readPort-prefers-json") {
    val workspace = Files.createTempDirectory("metals-mcp-test")
    val projectPath = AbsolutePath(workspace)

    // Create both files, json should be preferred
    val jsonFile = projectPath.resolve("opencode.json")
    val jsoncFile = projectPath.resolve("opencode.jsonc")

    val jsonConfig = """{
                       |  "mcp": {
                       |    "metals-lsp": {
                       |      "url": "http://localhost:1111/mcp"
                       |    }
                       |  }
                       |}""".stripMargin
    val jsoncConfig = """{
                        |  "mcp": {
                        |    "metals-lsp": {
                        |      "url": "http://localhost:2222/mcp"
                        |    }
                        |  }
                        |}""".stripMargin

    Files.write(jsonFile.toNIO, jsonConfig.getBytes(StandardCharsets.UTF_8))
    Files.write(jsoncFile.toNIO, jsoncConfig.getBytes(StandardCharsets.UTF_8))

    // Should read from opencode.json (1111), not jsonc (2222)
    assertEquals(
      McpConfig.readPort(projectPath, "test-project", OpenCode),
      Some(1111),
    )
  }

  test("getPort - reads port from old /sse endpoint") {
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
}
