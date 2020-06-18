package scala.meta.internal.mtags

import java.net.URL
import java.net.URLClassLoader
import java.util

import scala.collection.Seq
import scala.util.Try

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.io.AbsolutePath
import scala.meta.io.Classpath
import scala.meta.io.RelativePath

import sun.misc.Unsafe

object ClasspathLoader {

  /**
   * Utility to get SystemClassLoader/ClassLoader urls in java8 and java9+
   *   Based upon: https://gist.github.com/hengyunabc/644f8e84908b7b405c532a51d8e34ba9
   */
  def getURLs(classLoader: ClassLoader): Seq[URL] = {
    if (classLoader.isInstanceOf[URLClassLoader]) {
      classLoader.asInstanceOf[URLClassLoader].getURLs()
      // java9+
    } else if (
      classLoader
        .getClass()
        .getName()
        .startsWith("jdk.internal.loader.ClassLoaders$")
    ) {
      try {
        val field = classOf[Unsafe].getDeclaredField("theUnsafe")
        field.setAccessible(true)
        val unsafe = field.get(null).asInstanceOf[Unsafe]

        // jdk.internal.loader.ClassLoaders.AppClassLoader.ucp
        val ucpField = classLoader.getClass().getDeclaredField("ucp")
        val ucpFieldOffset: Long = unsafe.objectFieldOffset(ucpField)
        val ucpObject = unsafe.getObject(classLoader, ucpFieldOffset)

        // jdk.internal.loader.URLClassPath.path
        val pathField = ucpField.getType().getDeclaredField("path")
        val pathFieldOffset = unsafe.objectFieldOffset(pathField)
        val paths: Seq[URL] = unsafe
          .getObject(ucpObject, pathFieldOffset)
          .asInstanceOf[util.ArrayList[URL]]
          .asScala

        paths
      } catch {
        case ex: Exception =>
          ex.printStackTrace()
          Nil
      }
    } else {
      Nil
    }
  }
}

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

  /**
   * Load a resource from the classpath. */
  def load(path: RelativePath): Option[AbsolutePath] = {
    loader.resolve(path)
  }

  def loadClass(symbol: String): Option[Class[_]] = {
    Try(loader.loadClass(symbol)).toOption
  }

  /**
   * Load a resource from the classpath. */
  def load(path: String): Option[AbsolutePath] = {
    loader.resolve(path)
  }
}
