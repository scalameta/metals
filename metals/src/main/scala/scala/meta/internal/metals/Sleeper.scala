package scala.meta.internal.metals

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.FiniteDuration

trait Sleeper {
  def sleep(duration: FiniteDuration): Future[Unit]
}
object Sleeper {

  object TestingSleeper extends Sleeper {
    override def sleep(duration: FiniteDuration): Future[Unit] = {
      // No sleep
      Future.unit
    }
  }
  class ScheduledExecutorServiceSleeper(sh: ScheduledExecutorService)
      extends Sleeper {
    override def sleep(duration: FiniteDuration): Future[Unit] = {
      val promise = Promise[Unit]()
      sh.schedule(
        new Runnable {
          override def run(): Unit = promise.trySuccess(())
        },
        duration.toMillis,
        TimeUnit.MILLISECONDS,
      )
      promise.future
    }
  }

}
