package scala.meta.internal.mtags

import java.net.URLClassLoader
import java.nio.file.Path
import java.nio.file.Paths

import scala.collection.mutable
import scala.util.control.NonFatal

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.mtags.CommonMtagsEnrichments.XtensionNIOPath

final class OpenClassLoader extends URLClassLoader(Array.empty) {
  private val isAdded = mutable.Set.empty[Path]

  override def toString: String = super.getURLs.toList.toString()

  def addClasspath(classpath: List[Path]): Boolean =
    classpath.forall(addEntry)

  def addEntry(entry: Path): Boolean = {
    if (!isAdded(entry)) {
      super.addURL(entry.toUri.toURL)
      isAdded += entry
      true
    } else {
      false
    }
  }

  def resolve(uri: String): Option[Path] = {
    val enumeration = super.findResources(uri)
    if (enumeration.hasMoreElements) {
      val url = enumeration.nextElement()
      Some(Paths.get(url.toURI))
    } else {
      None
    }
  }
  def resolveAll(uri: String): List[Path] = {
    val enumeration = super.findResources(uri)
    enumeration.asScala.toList.map(url => Paths.get(url.toURI))
  }

  def resolve(relpath: Path): Option[Path] = {
    val uri = relpath.toURI(isDirectory = false).toString
    resolve(uri)
  }

  def loadClassSafe(symbol: String): Option[Class[_]] =
    try {
      Some(loadClass(symbol))
    } catch {
      case _: ClassNotFoundException => None
      case NonFatal(_) => None
    }

}
