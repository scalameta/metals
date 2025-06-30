package scala.meta.internal.metals.mcp

import scala.util.Try

import scala.meta.internal.metals.JsonParser.XtensionSerializedAsOption
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser

object McpConfig {
  val gson: Gson = new GsonBuilder()
    .setPrettyPrinting()
    .create()

  def writeConfig(
      port: Int,
      projectName: String,
      projectPath: AbsolutePath,
      client: Client = CursorEditor,
  ): Unit = {
    val filename = client.fileName.getOrElse("mcp.json")
    val configFile = projectPath.resolve(s"${client.settingsPath}$filename")
    val serverName = client.serverEntry.getOrElse(s"$projectName-metals")

    // Read existing config if it exists
    val config = if (configFile.exists) configFile.readText else "{ }"
    val newConfig = createConfig(config, port, serverName, client)
    configFile.writeText(newConfig)
  }

  def createConfig(
      inputConfig: String,
      port: Int,
      serverEntry: String,
      editor: Client = CursorEditor,
  ): String = {
    val config = JsonParser.parseString(inputConfig).getAsJsonObject

    // Get or create mcpServers object
    val mcpServers = if (config.has(editor.serverField)) {
      config.getAsJsonObject(editor.serverField)
    } else {
      val newMcpServers = new JsonObject()
      config.add(editor.serverField, newMcpServers)
      newMcpServers
    }

    // Add or update the server config
    val serverConfig = new JsonObject()
    serverConfig.addProperty("url", s"http://localhost:$port/sse")
    editor.additionalProperties.foreach { case (key, value) =>
      serverConfig.addProperty(key, value)
    }
    mcpServers.add(serverEntry, serverConfig)
    gson.toJson(config)
  }

  def readPort(
      projectPath: AbsolutePath,
      projectName: String,
      editor: Client,
  ): Option[Int] = {
    val filename = editor.fileName.getOrElse("mcp.json")
    val configFile = projectPath.resolve(s"${editor.settingsPath}$filename")
    if (configFile.exists)
      getPort(configFile.readText, projectName, editor)
    else None
  }

  def getPort(
      configInput: String,
      projectName: String,
      editor: Client = CursorEditor,
  ): Option[Int] = {
    for {
      config <- Try(
        JsonParser.parseString(configInput).getAsJsonObject()
      ).toOption
      mcpServers <- config.getObjectOption(editor.serverField)
      serverConfig <- mcpServers.getObjectOption(s"$projectName-metals")
      url <- serverConfig.getStringOption("url")
      port <- Try(url.stripSuffix("/sse").split(":").last.toInt).toOption
    } yield port
  }

}

case class Client(
    names: List[String],
    settingsPath: String,
    serverField: String,
    additionalProperties: List[(String, String)],
    serverEntry: Option[String] = None,
    fileName: Option[String] = None,
)

object VSCodeEditor
    extends Client(
      names = List(
        "Visual Studio Code",
        "Visual Studio Code - Insiders",
        "VSCodium",
        "VSCodium - Insiders",
      ),
      settingsPath = ".vscode/",
      serverField = "servers",
      additionalProperties = List(
        "type" -> "sse"
      ),
    )

object CursorEditor
    extends Client(
      names = List("Cursor"),
      settingsPath = ".cursor/",
      serverField = "mcpServers",
      additionalProperties = Nil,
    )

object Claude
    extends Client(
      names = List("claude", "Claude Code", "claude-code"),
      settingsPath = "./",
      serverField = "mcpServers",
      additionalProperties = List(
        "type" -> "sse"
      ),
      serverEntry = Some("metals"),
      fileName = Some(".mcp.json"),
    )

object NoClient
    extends Client(
      names = Nil,
      settingsPath = ".metals/",
      serverField = "servers",
      additionalProperties = Nil,
    )

object Client {
  val allClients: List[Client] = List(VSCodeEditor, CursorEditor, Claude)
}
