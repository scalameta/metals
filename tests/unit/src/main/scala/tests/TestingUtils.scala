package tests

import org.eclipse.{lsp4j => l}
import scala.meta.io.AbsolutePath

object TestingUtils {

  def compatJDK(locs: List[l.Location], isJava8: Boolean): List[l.Location] = {
    locs.map(loc =>
      if (isJava8) loc
      else {
        new l.Location(
          loc.getUri.replaceAllLiterally("java.base/", ""),
          loc.getRange
        )
      }
    )
  }

  def uriToRelative(uri: String, root: AbsolutePath): String =
    uri.stripPrefix(root.toURI.toString)

  def locationToString(loc: l.Location, root: AbsolutePath): String = {
    def locationToRelative(loc: l.Location, root: AbsolutePath): l.Location = {
      val uri = uriToRelative(loc.getUri, root)
      new l.Location(uri, loc.getRange)
    }

    val (uri, start, end) =
      (
        locationToRelative(loc, root).getUri,
        loc.getRange.getStart,
        loc.getRange.getEnd
      )
    s"$uri [${start.getLine}:${start.getCharacter} -> ${end.getLine}:${end.getCharacter}]"
  }

  def locationFromString(
      str: String,
      root: AbsolutePath
  ): Option[l.Location] = {
    val regex =
      """\s*([^\s]+)\s* \[([0-9]+):([0-9]+)\s* ->\s* ([0-9]+):([0-9]+)\]\s*""".r

    def createLocation(
        uri: String,
        begin: (Int, Int),
        end: (Int, Int)
    ): l.Location = {
      val startPos = new l.Position(begin._1, begin._2)
      val endPos = new l.Position(end._1, end._2)

      new l.Location(uri, new l.Range(startPos, endPos))
    }

    str match {
      case regex(uri, startL, startC, endL, endC) =>
        Some(
          createLocation(
            root.toURI.resolve(uri).toString.replaceFirst("/", "///"),
            (startL.toInt, startC.toInt),
            (endL.toInt, endC.toInt)
          )
        )
      case _ =>
        None
    }
  }

  def prepareDefinition(original: String, removeAt: Boolean = false): String =
    if (removeAt) original.replaceAll("(<<)?(>>)?(@@)?", "")
    else original.replaceAll("(<<)?(>>)?", "")

}
