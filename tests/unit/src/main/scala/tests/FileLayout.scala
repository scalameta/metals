package tests

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption

import scala.meta.internal.metals.MetalsEnrichments.XtensionString
import scala.meta.io.AbsolutePath

object FileLayout {

  case class Query(filename: String, query: String, uniqueString: String)

  def queriesFromFiles(
      files: Map[String, String],
      uniqueSeed: String = "seed",
  ): Seq[Query] = {
    val singleQuery = files
      .find(_._2.contains("@@"))

    val queries = singleQuery match {
      case None =>
        files.flatMap { case (file, code) =>
          code.indicesOf("<<").map { ind =>
            val updated =
              code.substring(0, ind + 3) + "@@" + code.substring(ind + 3)
            Query(file, updated, uniqueSeed + ind)
          }

        }
      case Some((filename, edit)) => List(Query(filename, edit, uniqueSeed))
    }
    queries.toSeq
  }

  def mapFromString(layout: String): Map[String, String] = {
    if (!layout.trim.isEmpty) {
      val lines = layout.replace("\r\n", "\n")
      lines
        .split("(?=\n/[^/*])")
        .map { row =>
          row.stripPrefix("\n").split("\n", 2).toList match {
            case path :: contents :: Nil =>
              path.stripPrefix("/") -> contents
            case els =>
              throw new IllegalArgumentException(
                s"Unable to split argument info path/contents! \n$els"
              )
          }
        }
        .toMap
    } else {
      Map.empty
    }
  }

  def fromString(
      layout: String,
      root: AbsolutePath = AbsolutePath(Files.createTempDirectory("scalameta")),
      charset: Charset = StandardCharsets.UTF_8,
  ): AbsolutePath = {
    if (!layout.trim.isEmpty) {
      mapFromString(layout).foreach { case (path, contents) =>
        val file =
          path.split("/").foldLeft(root)(_ resolve _)
        val parent = file.toNIO.getParent
        if (!Files.exists(parent)) { // cannot create directories when parent is a symlink
          Files.createDirectories(parent)
        }
        Files.deleteIfExists(file.toNIO)
        Files.write(
          file.toNIO,
          contents.getBytes(charset),
          StandardOpenOption.WRITE,
          StandardOpenOption.CREATE,
        )
      }

      scribe.info(s"Layout written to $root")
    } else {
      scribe.info(s"Layout is empty, not writing anything to $root")
    }
    root
  }
}
