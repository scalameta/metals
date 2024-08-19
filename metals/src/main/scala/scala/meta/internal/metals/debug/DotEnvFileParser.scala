package scala.meta.internal.metals.debug

import java.nio.charset.Charset

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.matching.Regex
import scala.util.matching.Regex.Groups

import scala.meta.internal.io.FileIO
import scala.meta.internal.metals.MetalsEnrichments.given
import scala.meta.io.AbsolutePath

object DotEnvFileParser {
  // DotEnv file regex adapted from the following projects:
  //  - https://github.com/mefellows/sbt-dotenv
  //  - https://github.com/bkeepers/dotenv
  val LineRegex: Regex =
    """(?xms)
    ^                         # start of line
    \s*                       # leading whitespace
    (?:export\s+)?            # optional export
    ([a-zA-Z_]+[a-zA-Z0-9_]*) # key
    (?:\s*=\s*|\s*\:\s*)      # separator (= or :)
    (                         # value capture group
      '(?:\\'|[^'])*'         # single quoted value or
      |
      "(?:\\"|[^"])*"         # double quoted value or
      |
      [^\r\n]*                # unquoted value    
    )
    \s*                       # trailing whitespace
    (?:\#[^\n]*)?                 # optional comment
    $                         # end of line
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
