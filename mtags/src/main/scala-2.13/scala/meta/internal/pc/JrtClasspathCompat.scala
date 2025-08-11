package scala.meta.internal.pc

import java.util.logging.Logger

import scala.tools.nsc.classpath.FileBasedCache
import scala.tools.nsc.classpath.JrtClassPath

object JrtClasspathCompat {

  /**
   * It seems that sometimes the JrtClassPath or CtSymClassPath caches get corrupted
   * and since it's an object it never gets removed or cleared even when restarting
   * the compiler.
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

      jrtClassPathCache.clear()

      val ctSymClassPathCacheField =
        jrtClassPathClass.getDeclaredField("ctSymClassPathCache")
      ctSymClassPathCacheField.setAccessible(true)
      val ctSymClassPathCache = ctSymClassPathCacheField
        .get(null)
        .asInstanceOf[FileBasedCache[_, _]]

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

}
