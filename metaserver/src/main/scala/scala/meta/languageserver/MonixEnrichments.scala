package scala.meta.languageserver

import scala.concurrent.Future
import monix.execution.Ack
import monix.execution.Scheduler
import monix.reactive.Observable
import monix.reactive.observers.Subscriber

object MonixEnrichments {
  implicit class XtensionObservableScalameta[A](val o: Observable[A])
      extends AnyVal {
    def onNext[B](f: (Subscriber[B], A) => Future[Ack]): Observable[B] = {
      o.liftByOperator[B] { out =>
        new Subscriber[A] {
          override implicit def scheduler: Scheduler = out.scheduler
          override def onError(ex: Throwable): Unit = out.onError(ex)
          override def onComplete(): Unit = out.onComplete()
          override def onNext(elem: A): Future[Ack] = f(out, elem)
        }
      }
    }
  }

}
