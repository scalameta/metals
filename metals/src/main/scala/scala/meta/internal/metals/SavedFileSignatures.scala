package scala.meta.internal.metals

import java.io.IOException

import scala.collection.concurrent.TrieMap

import scala.meta.internal.io.FileIO
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.MD5
import scala.meta.io.AbsolutePath

class SavedFileSignatures {
  private val previousCreateOrModify = TrieMap[AbsolutePath, String]()

  def didSavedContentChanged(pathWithContent: PathWithContent): Boolean = {
    val path = pathWithContent.path
    pathWithContent
      .getSignature()
      .map { md5 =>
        synchronized {
          if (previousCreateOrModify.get(path) == md5) false
          else {
            md5 match {
              case None => previousCreateOrModify.remove(path)
              case Some(md5) => previousCreateOrModify.put(path, md5)
            }
            true
          }
        }
      }
      .getOrElse(true)
  }

  def cancel(): Unit = previousCreateOrModify.clear()
}

class PathWithContent(
    val path: AbsolutePath,
    optContent: Option[PathWithContent.Content],
) {
  def getSignature(): Either[IOException, Option[String]] = {
    optContent
      .map(_.map(content => MD5.bytesToHex(content)))
      .map(Right[IOException, Option[String]](_))
      .getOrElse {
        try {
          if (path.exists)
            Right(Some(MD5.bytesToHex(FileIO.readAllBytes(path))))
          else Right(None)
        } catch {
          case e: IOException =>
            scribe.warn(s"Failed to read contents of $path", e)
            Left(e)
        }
      }
  }
}

object PathWithContent {
  // None if the file doesn't exist
  type Content = Option[Array[Byte]]
  def apply(path: AbsolutePath) = new PathWithContent(path, None)
  def apply(path: AbsolutePath, content: Array[Byte]) =
    new PathWithContent(path, Some(Some(content)))
  def deleted(path: AbsolutePath) = new PathWithContent(path, Some(None))
}
