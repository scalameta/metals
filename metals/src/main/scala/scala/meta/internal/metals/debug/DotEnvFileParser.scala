package scala.meta.internal.metals.debug

import java.nio.charset.Charset

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.matching.Regex
import scala.util.matching.Regex.Groups

import scala.meta.internal.io.FileIO
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

object DotEnvFileParser {
  // DotEnv file regex adapted from the following project: https://github.com/Philippus/sbt-dotenv
  val LineRegex: Regex =
    """(?xms)
        (?:^|\A)           # start of line
        \s*                # leading whitespace
        (?:export\s+)?     # export (optional)
        (                  # start variable name (captured)
          [a-zA-Z_]          # single alphabetic or underscore character
          [a-zA-Z0-9_]*    # zero or more alphnumeric, underscore
        )                  # end variable name (captured)
        (?:\s*[=:]\s*?)       # assignment with whitespace
        (                  # start variable value (captured)
          '(?:\\'|[^'])*'    # single quoted variable
          |                  # or
          "(?:\\"|[^"])*"    # double quoted variable
          |                  # or
          [^\#\r\n]*         # unquoted variable
        )                  # end variable value (captured)
        \s*                # trailing whitespace
        (?:                # start trailing comment (optional)
          \#                 # begin comment
          (?:(?!$).)*        # any character up to end-of-line
        )?                 # end trailing comment (optional)
        (?:$|\z)           # end of line
  """.r

  def parse(
      path: AbsolutePath
  )(implicit ec: ExecutionContext): Future[Map[String, String]] =
    if (path.exists && path.isFile && path.toFile.canRead)
      Future(parse(FileIO.slurp(path, Charset.defaultCharset)))
    else
      Future.failed(InvalidEnvFileException(path))

  def parse(content: String): Map[String, String] = {
    LineRegex
      .findAllMatchIn(content)
      .map(_ match { case Groups(k, v) => (k -> unescape(unquote(v))) })
      .toMap
  }

  private def unquote(value: String): String = {
    value.trim match {
      case s if startsEndsWith(s, "'") => trim(s).trim
      case s if startsEndsWith(s, "\"") => trim(s).trim
      case s => s
    }
  }

  private def startsEndsWith(s: String, prefixSuffix: String): Boolean =
    s.startsWith(prefixSuffix) && s.endsWith(prefixSuffix)

  private def trim(s: String): String =
    if (s.length > 1) s.substring(1, s.length - 1)
    else s

  private def unescape(s: String): String =
    s.replaceAll("""\\([^$])""", "$1")

  case class InvalidEnvFileException(path: AbsolutePath)
      extends Exception(
        s"Unable to open the specified .env file ${path.toString()}. " +
          "Please make sure the file exists and is readable."
      )
}
