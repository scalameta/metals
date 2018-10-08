package scala.meta.internal.mtags

import java.net.URLClassLoader
import java.nio.file.Paths
import scala.meta.io.AbsolutePath
import scala.meta.io.RelativePath

class OpenClassLoader extends URLClassLoader(Array.empty) {
  def addEntry(entry: AbsolutePath): Unit = {
    super.addURL(entry.toNIO.toUri.toURL)
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
