package scala.meta.internal.metals.concurrent

import java.util.concurrent.ConcurrentHashMap

import scala.meta.io.AbsolutePath

/**
 * Cache, where multiple files can write at the same time, but only one file can delete at a time.
 */
object FileLock {

  private val jarLocks =
    new ConcurrentHashMap[AbsolutePath, JarLock]()

  def withWriteLock[A](dir: AbsolutePath)(f: () => A)(fallback: => A): A = {
    val lock = jarLocks.compute(
      dir,
      (_, lock: JarLock) => {
        if (lock == null) JarLock(0, 1)
        else if (lock.deleteLock == 0) lock.copy(writeLock = lock.writeLock + 1)
        else lock
      },
    )
    // 0 means that we are not deleting the file
    if (lock.deleteLock != 0) {
      fallback
    } else {
      try {
        f()
      } finally {
        jarLocks.compute(
          dir,
          (_, lock: JarLock) => {
            if (lock == null) null
            // remove the lock since no longer needed
            else if (lock.writeLock - 1 == 0) null
            else lock.copy(writeLock = lock.writeLock - 1)
          },
        )
      }
    }
  }

  def withDeleteLock[A](dir: AbsolutePath)(f: () => A)(fallback: => A): A = {
    val lock = jarLocks.compute(
      dir,
      (_, lock: JarLock) => {
        if (lock == null) JarLock(1, 0)
        else if (lock.writeLock == 0)
          lock.copy(deleteLock = lock.deleteLock + 1)
        else lock
      },
    )

    // 0 means we didn't get the lock, 2 and more means we tried to get multiple delete locks
    if (lock.deleteLock != 1) {
      fallback
    } else
      try {
        f()
      } finally {
        jarLocks.remove(dir)
      }

  }

  case class JarLock(deleteLock: Int, writeLock: Int)

}
