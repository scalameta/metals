package scala.meta.internal.mtags

import scala.meta.io.AbsolutePath
import scala.meta.io.Classpath
import scala.meta.io.RelativePath

/**
 * Utility to load relative paths from a classpath.
 *
 * Provides similar functionality as URLClassLoader but uses idiomatic
 * Scala data structures like `AbsolutePath` and `Option[T]` instead of
 * `java.net.URL` and nulls.
 */
final class ClasspathLoader(classpath: Classpath) {
  val loader = new OpenClassLoader
  classpath.entries.foreach(loader.addEntry)
  override def toString: String = loader.getURLs.toList.toString()

  def addEntry(entry: AbsolutePath): Unit = {
    loader.addEntry(entry)
  }

  /** Load a resource from the classpath. */
  def load(path: RelativePath): Option[AbsolutePath] = {
    loader.resolve(path)
  }

  /** Load a resource from the classpath. */
  def load(path: String): Option[AbsolutePath] = {
    loader.resolve(path)
  }
}
