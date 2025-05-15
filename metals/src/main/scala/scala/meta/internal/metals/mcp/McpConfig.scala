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
      editor: Editor = CursorEditor,
  ): Unit = {
    val configFile = editor.configFilePath(projectPath)

    // Read existing config if it exists
    val config = if (configFile.exists) configFile.readText else "{ }"
    val newConfig = createConfig(config, port, projectName, editor)
    configFile.writeText(newConfig)
  }

  def createConfig(
      inputConfig: String,
      port: Int,
      projectName: String,
      editor: Editor = CursorEditor,
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
    mcpServers.add(s"$projectName-metals", serverConfig)
    gson.toJson(config)
  }

  def readPort(
      projectPath: AbsolutePath,
      projectName: String,
      editor: Editor,
  ): Option[Int] = {
    val configFile = editor.configFilePath(projectPath)
    if (configFile.exists)
      getPort(configFile.readText, projectName, editor)
    else None
  }

  def getPort(
      configInput: String,
      projectName: String,
      editor: Editor = CursorEditor,
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

case class Editor(
    name: String,
    settingsPath: SettingsPath,
    configFileName: String,
    serverField: String,
    additionalProperties: List[(String, String)],
) {
  def configFilePath(projectPath: AbsolutePath): AbsolutePath =
    settingsPath.resolve(projectPath, path = configFileName)
}

sealed trait SettingsPath {

  /** Resolves the path to the settings file. */
  def resolve(projectPath: AbsolutePath, path: String): AbsolutePath =
    this match {
      case SettingsPath.ProjectRelative(settingsPath) =>
        projectPath.resolve(settingsPath).resolve(path)

      case SettingsPath.UserHomeRelative(settingsPath) =>
        val home = AbsolutePath(System.getProperty("user.home"))
        home.resolve(settingsPath).resolve(path)
    }
}
object SettingsPath {

  /** Relative to the project root */
  case class ProjectRelative(path: String) extends SettingsPath

  /** Relative to the user home */
  case class UserHomeRelative(path: String) extends SettingsPath
}

object VSCodeEditor
    extends Editor(
      name = "Visual Studio Code",
      settingsPath = SettingsPath.ProjectRelative(".vscode/"),
      configFileName = "mcp.json",
      serverField = "servers",
      additionalProperties = List(
        "type" -> "sse"
      ),
    )

/** https://www.cursor.com/ */
object CursorEditor
    extends Editor(
      name = "Cursor",
      settingsPath = SettingsPath.UserHomeRelative(".cursor/"),
      configFileName = "mcp.json",
      serverField = "mcpServers",
      additionalProperties = Nil,
    )

/** https://windsurf.com/ */
object WindsurfEditor
    extends Editor(
      name = "Windsurf",
      settingsPath = SettingsPath.UserHomeRelative(".codeium/windsurf/"),
      configFileName = "mcp_config.json",
      serverField = "mcpServers",
      additionalProperties = Nil,
    )

object Editor {
  val allEditors: List[Editor] =
    List(VSCodeEditor, CursorEditor, WindsurfEditor)
}
