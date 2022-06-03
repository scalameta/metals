package scala.meta.internal.mtags

import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Paths

import scala.collection.JavaConverters._
import scala.collection.mutable

import scala.meta.io.AbsolutePath
import scala.meta.io.RelativePath

final class OpenClassLoader extends URLClassLoader(Array.empty) {
  private val isAdded = mutable.Set.empty[AbsolutePath]
  def addEntry(entry: AbsolutePath): Boolean = {
    if (!isAdded(entry)) {
      // url must end in "/" for the correct Loader to be selected for metalsfs:/somejar.jar
      val url = entry.toNIO.toUri.toURL
      val formattedURL = new URL(s"${url}/")
      super.addURL(formattedURL)
      isAdded += entry
      true
    } else {
      false
    }
  }

  def resolve(uri: String): Option[AbsolutePath] = {
    val enumeration = super.findResources(uri)
    if (enumeration.hasMoreElements) {
      val url = enumeration.nextElement()
      Some(AbsolutePath(Paths.get(url.toURI)))
    } else {
      None
    }
  }
  def resolveAll(uri: String): List[AbsolutePath] = {
    val enumeration = super.findResources(uri)
    enumeration.asScala.toList.map(url => AbsolutePath(Paths.get(url.toURI)))
  }
  def resolve(relpath: RelativePath): Option[AbsolutePath] = {
    val uri = relpath.toURI(isDirectory = false).toString
    resolve(uri)
  }
}
