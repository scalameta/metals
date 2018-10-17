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
final class ClasspathLoader() {
  val loader = new OpenClassLoader
  def close(): Unit = loader.close()
  override def toString: String = loader.getURLs.toList.toString()

  def addClasspath(classpath: Classpath): Boolean = {
    classpath.entries.forall(addEntry)
  }
  def addEntry(entry: AbsolutePath): Boolean = {
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
