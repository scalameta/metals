package scala.meta.internal.metals

import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.RejectedExecutionHandler

object ThreadPools {
  def discardRejectedRunnables(where: String, executor: ExecutorService): Unit =
    executor match {
      case t: ThreadPoolExecutor =>
        t.setRejectedExecutionHandler(new RejectedExecutionHandler {
          def rejectedExecution(
              r: Runnable,
              executor: ThreadPoolExecutor
          ): Unit = {
            scribe.info(s"rejected runnable: $where")
          }
        })
      case _ =>
    }
}
