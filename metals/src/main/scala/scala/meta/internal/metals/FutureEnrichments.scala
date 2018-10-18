package scala.meta.internal.metals

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.Duration

object FutureEnrichments {
  implicit class XtensionFuture[A](fut: Future[A]) {
    def get(duration: Duration = Duration(30, "s")): A =
      Await.result(fut, duration)
  }

}
