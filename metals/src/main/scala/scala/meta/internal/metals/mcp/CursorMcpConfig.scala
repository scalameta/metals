package scala.meta.internal.metals.mcp

import scala.util.Try

import scala.meta.internal.metals.JsonParser.XtensionSerializedAsOption
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser

object CursorMcpConfig {
  val gson: Gson = new GsonBuilder()
    .setPrettyPrinting()
    .create()

  def writeConfig(
      port: Int,
      projectName: String,
      projectPath: AbsolutePath,
  ): Unit = {
    val configFile = projectPath.resolve(".cursor/mcp.json")

    // Read existing config if it exists
    val config = if (configFile.exists) configFile.readText else "{ }"
    val newConfig = writeConfig(config, port, projectName)
    configFile.writeText(newConfig)
  }

  def writeConfig(
      inputConfig: String,
      port: Int,
      projectName: String,
  ): String = {
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

  def readPort(projectPath: AbsolutePath, projectName: String): Option[Int] = {
    val configFile = projectPath.resolve(".cursor/mcp.json")
    if (configFile.exists)
      getPort(configFile.readText, projectName)
    else None
  }

  def getPort(configInput: String, projectName: String): Option[Int] = {
    for {
      config <- Try(
        JsonParser.parseString(configInput).getAsJsonObject()
      ).toOption
      mcpServers <- config.getObjectOption("mcpServers")
      serverConfig <- mcpServers.getObjectOption(s"$projectName-metals")
      url <- serverConfig.getStringOption("url")
      port <- Try(url.stripSuffix("/sse").split(":").last.toInt).toOption
    } yield port
  }

}
