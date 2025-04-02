package scala.meta.internal.metals.mcp

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath
import com.google.gson.{JsonObject, JsonParser, GsonBuilder}

object CursorMcpConfig {
  val gson = new GsonBuilder()
    .setPrettyPrinting()
    .create()

  def generateConfig(
      port: Int,
      projectName: String,
      projectPath: AbsolutePath,
  ): Unit = {
    val configFile = projectPath.resolve(".cursor/mcp.json")

    // Read existing config if it exists
    val config = if (configFile.exists) configFile.readText else "{ }"
    val newConfig = generate(config, port, projectName)
    configFile.writeText(newConfig)
  }

  def generate(inputConfig: String, port: Int, projectName: String): String = {
    val config = JsonParser.parseString(inputConfig).getAsJsonObject

    // Get or create mcpServers object
    val mcpServers = if (config.has("mcpServers")) {
      config.getAsJsonObject("mcpServers")
    } else {
      val newMcpServers = new JsonObject()
      config.add("mcpServers", newMcpServers)
      newMcpServers
    }

    // Add or update the server config
    val serverConfig = new JsonObject()
    serverConfig.addProperty("url", s"http://localhost:$port/sse")
    mcpServers.add(s"$projectName-metals", serverConfig)

    gson.toJson(config)
  }
}
