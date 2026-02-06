package tests.mcp

import scala.meta.internal.metals.mcp.TomlConfig

class TomlConfigSuite extends munit.FunSuite {

  test("upsert-server-new") {
    val original = ""
    val updated = TomlConfig.upsertServer(
      original,
      "mcpServers",
      "metals",
      Map("url" -> "http://localhost:123"),
    )
    assert(updated.contains("[mcpServers.metals]"))
    assert(updated.contains("""url = "http://localhost:123""""))
  }

  test("upsert-server-existing") {
    val original =
      """
        |[other]
        |foo = "bar"
        |
        |[mcpServers.metals]
        |url = "old"
        |
        |[another]
      """.stripMargin
    val updated = TomlConfig.upsertServer(
      original,
      "mcpServers",
      "metals",
      Map("url" -> "new"),
    )
    assert(updated.contains("""url = "new""""))
    assert(!updated.contains("""url = "old""""))
    assert(updated.contains("""[other]"""))
    assert(updated.contains("""[another]"""))
  }

  test("remove-server") {
    val original =
      """
        |[mcpServers.metals]
        |url = "http://localhost:123"
      """.stripMargin
    val updated = TomlConfig.removeServer(original, "mcpServers", "metals")
    assert(!updated.contains("[mcpServers.metals]"))
  }

  test("remove-server-preserve-others") {
    val original =
      """
        |[mcpServers.other]
        |val = 1
        |
        |[mcpServers.metals]
        |url = "http://localhost:123"
        |
        |[next]
      """.stripMargin
    val updated = TomlConfig.removeServer(original, "mcpServers", "metals")
    assert(!updated.contains("[mcpServers.metals]"))
    assert(updated.contains("[mcpServers.other]"))
    assert(updated.contains("[next]"))
  }

  test("read-port") {
    val toml =
      """
        |[mcpServers.my-project-metals]
        |url = "http://localhost:8080/mcp"
      """.stripMargin

    val port =
      TomlConfig.readPort(toml, "mcpServers", "my-project-metals", "/mcp")
    assertEquals(port, Some(8080))
  }
}
