package org.langmeta.lsp

import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import com.typesafe.scalalogging.Logger
import monix.execution.Ack
import monix.execution.Cancelable
import monix.execution.Scheduler
import monix.reactive.Observable
import monix.reactive.Observer

object MonixEnrichments {

  /**
   * Utility to read the latest eagerly computed value from an observable.
   *
   * NOTE. Immediately subscribes to the value and eagerly computes the value on every update.
   * Subscription can be cancelled with .cancel()
   *
   * @param obs The observable to convert into a reactive variable.
   * @param s The scheduler to compute the variable on.
   */
  class ObservableCurrentValue[+A](obs: Observable[A])(implicit s: Scheduler)
      extends (() => A)
      with Cancelable {
    private var value: Any = _
    private val cancelable = obs.foreach(newValue => value = newValue)
    override def apply(): A = {
      if (value == null) {
        throw new NoSuchElementException(
          "Reading from empty Observable, consider using MulticastStrategy.behavior(initialValue)"
        )
      } else {
        value.asInstanceOf[A]
      }
    }
    override def cancel(): Unit = cancelable.cancel()
  }

  implicit class XtensionObservable[A](val obs: Observable[A]) extends AnyVal {

    def focus[B](f: A => B): Observable[B] =
      obs.distinctUntilChangedByKey(f).map(f)

    def toFunction0()(implicit s: Scheduler): () => A =
      toObservableCurrentValue()

    def toObservableCurrentValue()(
        implicit s: Scheduler
    ): ObservableCurrentValue[A] =
      new ObservableCurrentValue[A](obs)
  }

  implicit class XtensionObserverCompanion[A](val `_`: Observer.type)
      extends AnyVal {
    def fromOutputStream(
        out: OutputStream,
        logger: Logger
    ): Observer.Sync[ByteBuffer] = {
      new Observer.Sync[ByteBuffer] {
        private[this] var isClosed: Boolean = false
        override def onNext(elem: ByteBuffer): Ack = {
          if (isClosed) Ack.Stop
          else {
            try {
              while (elem.hasRemaining) out.write(elem.get())
              out.flush()
              Ack.Continue
            } catch {
              case _: IOException =>
                logger.error("OutputStream closed!")
                isClosed = true
                Ack.Stop
            }
          }
        }
        override def onError(ex: Throwable): Unit = ()
        override def onComplete(): Unit = out.close()
      }
    }
  }

}
