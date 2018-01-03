package scala.meta.languageserver

import monix.execution.Cancelable
import monix.execution.Scheduler
import monix.reactive.Observable

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

}
