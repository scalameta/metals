package scala.meta.internal.metals

import java.io.IOException

import scala.collection.concurrent.TrieMap

import scala.meta.internal.io.FileIO
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.MD5
import scala.meta.io.AbsolutePath

class SavedFileSignatures {
  private val previousCreateOrModify = TrieMap[AbsolutePath, String]()

  def didSavedContentChanged(path: AbsolutePath): Boolean = {
    try {
      val md5 =
        if (path.exists) Some(MD5.bytesToHex(FileIO.readAllBytes(path)))
        else None
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
    } catch {
      case e: IOException =>
        scribe.warn(s"Failed to read contents of $path", e)
        true
    }
  }
}
