package scala.meta.internal.metals

import java.net.URI
import java.nio.file.FileSystem
import java.nio.file.FileSystemAlreadyExistsException
import java.nio.file.FileSystemNotFoundException
import java.nio.file.FileSystems

import scala.collection.mutable
import scala.util.control.NonFatal

import scala.meta.io.AbsolutePath

final case class FileSystemInfo(fs: FileSystem, fileUri: String)

final class JarFileSystemCache {

  private val openFileSystems = mutable.Map.empty[URI, FileSystem]

  /**
   * Synchronized to avoid a race where two threads both call newFileSystem
   * for the same archive.
   */
  def open(localPath: AbsolutePath): FileSystemInfo = synchronized {
    val fileUri = localPath.toNIO.toUri.toString.stripSuffix("/")
    val zipURI = JarFileSystemCache.jarUriFor(localPath)
    val fs = openFileSystems.getOrElseUpdate(zipURI, openFileSystem(zipURI))
    FileSystemInfo(fs, fileUri)
  }

  private def openFileSystem(zipURI: URI): FileSystem =
    try FileSystems.getFileSystem(zipURI)
    catch {
      case _: FileSystemNotFoundException =>
        try
          FileSystems
            .newFileSystem(zipURI, new java.util.HashMap[String, Any])
        catch {
          case _: FileSystemAlreadyExistsException =>
            FileSystems.getFileSystem(zipURI)
        }
    }

  def closeObsolete(knownPaths: Set[AbsolutePath]): Unit = synchronized {
    val knownUris = knownPaths.map(JarFileSystemCache.jarUriFor)
    val obsoleteUris = openFileSystems.keysIterator.filterNot(knownUris).toList
    obsoleteUris.foreach(close)
  }

  def close(zipURI: URI): Unit = synchronized {
    openFileSystems.remove(zipURI).foreach { fs =>
      try fs.close()
      catch {
        case NonFatal(e) =>
          scribe.warn(s"Failed to close jar file system $zipURI", e)
      }
    }
  }
}

object JarFileSystemCache {
  def jarUriFor(localPath: AbsolutePath): URI = {
    val fileUri = localPath.toNIO.toUri.toString.stripSuffix("/")
    URI.create(s"jar:$fileUri")
  }
}
