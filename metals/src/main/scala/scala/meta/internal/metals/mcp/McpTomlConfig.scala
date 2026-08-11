package scala.meta.internal.metals.mcp

import java.net.URI

import scala.util.Try

object McpTomlConfig {
  private val newline = System.lineSeparator()

  def upsertServer(
      input: String,
      tableName: String,
      serverName: String,
      url: String,
  ): String = {
    val section = List(s"[$tableName.$serverName]", s"""url = "$url"""")
    val lines = input.linesIterator.toList

    val updated = sectionBounds(lines, tableName, serverName) match {
      case Some((start, end)) =>
        val suffix = lines.drop(end)
        val separator = if (suffix.nonEmpty) List("") else Nil
        (lines.take(start) ++ section ++ separator ++ suffix)
      case None if lines.isEmpty => section
      case None =>
        val separator = if (lines.last.trim.isEmpty) Nil else List("")
        lines ++ separator ++ section
    }

    updated.mkString(newline)
  }

  def removeServer(
      input: String,
      tableName: String,
      serverName: String,
  ): String = {
    val lines = input.linesIterator.toList

    val updated =
      sectionBounds(lines, tableName, serverName) match {
        case None => lines
        case Some((start, end)) => lines.take(start) ++ lines.drop(end)
      }

    updated.mkString(newline)
  }

  def readPort(
      input: String,
      tableName: String,
      serverName: String,
  ): Option[Int] = {
    val lines = input.linesIterator.toList
    sectionBounds(lines, tableName, serverName).flatMap { case (start, end) =>
      lines
        .slice(start, end)
        .collectFirst {
          case line if line.takeWhile(_ != '=').trim == "url" =>
            line.dropWhile(_ != '=').drop(1).trim
        }
        .map(stripQuotes)
        .flatMap(url => Try(new URI(url).getPort).toOption)
        .filter(_ > 0)
    }
  }

  private def stripQuotes(value: String): String = {
    val trimmed = value.trim
    if (trimmed.startsWith("\"") && trimmed.endsWith("\""))
      trimmed.stripPrefix("\"").stripSuffix("\"")
    else if (trimmed.startsWith("'") && trimmed.endsWith("'"))
      trimmed.stripPrefix("'").stripSuffix("'")
    else trimmed
  }

  private def sectionBounds(
      lines: List[String],
      tableName: String,
      serverName: String,
  ): Option[(Int, Int)] = {
    val header = s"[$tableName.$serverName]"
    val start = lines.indexWhere(_.trim == header)

    if (start < 0) None
    else {
      val end = lines.zipWithIndex
        .collectFirst {
          case (line, index)
              if index > start && line.trim
                .startsWith("[") && line.trim.endsWith("]") =>
            index
        }
        .getOrElse(lines.length)

      Some((start, end))
    }
  }
}
