package scala.meta.internal.metals.mcp

import scala.util.Try

/**
 * A lightweight TOML editor that doesn't require a full parser.
 * It is designed to safely edit only the [mcpServers] section of a config.toml file,
 * preserving comments and other sections.
 */
object TomlConfig {

  def upsertServer(
      originalToml: String,
      tableName: String,
      serverName: String,
      properties: Map[String, String],
  ): String = {
    val lines = originalToml.linesIterator.toList
    val (before, section, after) = splitBySection(lines, tableName, serverName)

    val newSection = generateSection(tableName, serverName, properties)

    (before ++ newSection ++ after).mkString(System.lineSeparator())
  }

  def removeServer(
      originalToml: String,
      tableName: String,
      serverName: String,
  ): String = {
    val lines = originalToml.linesIterator.toList
    val (before, _, after) = splitBySection(lines, tableName, serverName)

    // If we removed the last entry, we might want to clean up newlines, but keeping it simple is safer
    (before ++ after).mkString(System.lineSeparator())
  }

  def readPort(
      originalToml: String,
      tableName: String,
      serverName: String,
      endpointSuffix: String,
  ): Option[Int] = {
    val lines = originalToml.linesIterator.toList
    val (_, section, _) = splitBySection(lines, tableName, serverName)

    section.find(_.trim.startsWith("url =")).flatMap { line =>
      Try {
        val url = line.split("=")(1).trim.stripPrefix("\"").stripSuffix("\"")
        val normalizedUrl = url.replace(endpointSuffix, "")
        normalizedUrl.split(":").last.toInt
      }.toOption
    }
  }

  // --- Internals ---

  private def generateSection(
      tableName: String,
      serverName: String,
      properties: Map[String, String],
  ): List[String] = {
    val header = s"[$tableName.$serverName]"
    val props = properties.map { case (k, v) =>
      s"""$k = "$v""""
    }.toList
    // Add a blank line before if needed? relying on split logic to handle spacing
    List(header) ++ props
  }

  /**
   * Splits the lines into:
   * 1. Everything before the target section
   * 2. The target section (if it exists)
   * 3. Everything after the target section
   *
   * A section for `[mcpServers.my-server]` starts with that header and ends at the next `[...]` header or EOF.
   */
  private def splitBySection(
      lines: List[String],
      tableName: String,
      serverName: String,
  ): (List[String], List[String], List[String]) = {
    val targetHeader = s"[$tableName.$serverName]"

    val before = List.newBuilder[String]
    val section = List.newBuilder[String]
    val after = List.newBuilder[String]

    var state = 0 // 0: before, 1: inside, 2: after

    for (line <- lines) {
      val trimmed = line.trim
      state match {
        case 0 => // Searching for start
          if (trimmed == targetHeader) {
            state = 1
            section += line
          } else {
            before += line
          }
        case 1 => // Inside section
          if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            // Found next section
            state = 2
            after += line
          } else {
            // Still in section (properties, comments, empty lines)
            section += line
          }
        case 2 => // After section
          after += line
      }
    }

    // Heuristic: If we never found it, we append to the end
    // But we need to make sure we are not appending to the middle of another table?
    // TOML allows tables anywhere.
    // If state is 0, it means it doesn't exist.
    if (state == 0) {
      // Return everything as 'before'. 'section' and 'after' are empty.
      // Callers will append the new section to 'before'.
      // We might want to ensure a newline separator if the file is not empty
      val safeBefore =
        if (lines.nonEmpty && lines.last.trim.nonEmpty) lines :+ "" else lines
      (safeBefore, Nil, Nil)
    } else {
      (before.result(), section.result(), after.result())
    }
  }
}
