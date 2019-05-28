package scala.meta.internal.metals

import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadPoolExecutor

object ThreadPools {
  def discardRejectedRunnables(where: String, executor: ExecutorService): Unit =
    executor match {
      case t: ThreadPoolExecutor =>
        t.setRejectedExecutionHandler((_, _) => {
          scribe.info(s"rejected runnable: $where")
        })
      case _ =>
    }
}
