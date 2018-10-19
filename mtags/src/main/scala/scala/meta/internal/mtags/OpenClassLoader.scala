package scala.meta.internal.mtags

import java.net.URLClassLoader
import java.nio.file.Paths
import scala.collection.mutable
import scala.meta.io.AbsolutePath
import scala.meta.io.RelativePath

class OpenClassLoader extends URLClassLoader(Array.empty) {
  private val isAdded = mutable.Set.empty[AbsolutePath]
  def addEntry(entry: AbsolutePath): Unit = {
    if (!isAdded(entry)) {
      super.addURL(entry.toNIO.toUri.toURL)
      isAdded += entry
    }
  }
  def resolve(uri: String): Option[AbsolutePath] = {
    Option(super.findResource(uri)).map { url =>
      AbsolutePath(Paths.get(url.toURI))
    }
  }
  def resolve(relpath: RelativePath): Option[AbsolutePath] = {
    val uri = relpath.toURI(isDirectory = false).toString
    resolve(uri)
  }
}
