package scala.meta.internal.pc

import java.io.Closeable
import java.util.logging.Logger

import scala.tools.nsc.classpath.CtSymClassPath
import scala.tools.nsc.classpath.FileBasedCache
import scala.tools.nsc.classpath.JrtClassPath
import scala.tools.nsc.util.ClassPath

object JrtClasspathCompat {

  /**
   * It seems that sometimes the JrtClassPath or CtSymClassPath caches get corrupted
   * and since it's an object it never gets removed or cleared even when restarting
   * the compiler..
   *
   * This should help in those cases, we'll test it on snapshot versions and maybe
   * backport to scala itself if it proves to be useful.
   *
   * @return true if the caches were cleared, false otherwise if any reflection failed
   */
  def clearJrtClassPathCaches(logger: Logger): Boolean = {
    try {
      val jrtClassPathClass = JrtClassPath.getClass()
      val jrtClassPathCacheField =
        jrtClassPathClass.getDeclaredField("jrtClassPathCache")
      jrtClassPathCacheField.setAccessible(true)
      val jrtClassPathCache =
        jrtClassPathCacheField.get(null).asInstanceOf[FileBasedCache[_, _]]

      closeAllFileSystems(jrtClassPathCache, logger)
      jrtClassPathCache.clear()

      val ctSymClassPathCacheField =
        jrtClassPathClass.getDeclaredField("ctSymClassPathCache")
      ctSymClassPathCacheField.setAccessible(true)
      val ctSymClassPathCache = ctSymClassPathCacheField
        .get(null)
        .asInstanceOf[FileBasedCache[_, _]]

      closeAllFileSystems(ctSymClassPathCache, logger)
      ctSymClassPathCache.clear()

      true
    } catch {
      case e: Exception =>
        logger.warning(
          s"Failed to clear JrtClassPath caches: ${e.getMessage}"
        )
        false
    }
  }

  /**
   * Make sure to close all the FileBasedCache instances. It uses reflection to
   * access the private fields of the FileBasedCache class. This might fail,
   * but  the only issue should be that a filesystem is not closed.
   */
  private def closeAllFileSystems(
      cache: FileBasedCache[_, _],
      logger: Logger
  ): Unit = {
    try {
      // Accessing: private val cache = collection.mutable.Map.empty[(K, Seq[Path]), Entry]
      val cacheClass = cache.getClass
      val innerCacheField = cacheClass.getDeclaredField(
        "scala$tools$nsc$classpath$FileBasedCache$$cache"
      )
      innerCacheField.setAccessible(true)
      val innerCache =
        innerCacheField.get(cache).asInstanceOf[collection.mutable.Map[_, _]]

      /**
       * Version before Scala 2.13.15 didn't have the close method on the JrtClassPath
       * and CtSymClassPath classes. So we need to run reflection to access FileSystem
       * and close it ourselves.
       */
      def closeFileSystem(
          cls: Class[_],
          clsPath: ClassPath,
          fieldName: String
      ): Unit = {
        val fs = cls.getDeclaredField(fieldName)
        fs.setAccessible(true)
        fs.get(clsPath).asInstanceOf[Closeable].close()
      }

      innerCache.synchronized {
        innerCache.values.foreach { entry =>
          // Entry class: case class Entry(k: K, stamps: Seq[Stamp], t: T)
          val value = entry.getClass().getDeclaredField("t")
          value.setAccessible(true)
          value.get(entry) match {
            case jrtClassPath: JrtClassPath =>
              closeFileSystem(jrtClassPath.getClass(), jrtClassPath, "fs")
            case ctClasspath: CtSymClassPath =>
              closeFileSystem(ctClasspath.getClass(), ctClasspath, "fileSystem")
            case _ => // ignore other values since they do not cache anything
          }
        }
      }
    } catch {
      case e: Throwable =>
        logger.warning(
          s"Failed to close JrtClassPath or CtSymClassPath cache entries: ${e.getMessage}"
        )
        throw e
    }
  }
}
