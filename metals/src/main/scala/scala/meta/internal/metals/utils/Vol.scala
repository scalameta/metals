package scala.meta.internal.metals.utils

import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * This monad represents a volatile value that may change in real time (e.g. updated by another thread).
 *
 * The evaluation happens when the method snapshot() is called.
 * However, within a single for-comprehension the value of each Vol is evaluated only once.
 * As a result, a for-comprehension over Vol represents a consistent snapshot of some volatile set of variables,
 * which has existed at some recent point in time.
 *
 * Instances of this trait are safe to pass across different threads, but its exposed methods are <b>not</b> thread safe.
 *
 * Unlike scala.concurrent.Future, the evaluation happens synchronously in the same thread that invokes it.
 * Unlike scala.concurrent.Promise or a lazy val, the value may change more than once.
 * Unlike cats.effect.IO, the evaluation is assumed to be fast and non-blocking.
 * Unlike geny.Generator, the evaluation happens on demand.
 */
trait Vol[+T <: Any] {

  protected def eval(): T

  def snapshot(): T = {
    val existing = Vol.context.get()
    if (existing eq null) {
      Vol.context.set(new IdentityHashMap[Vol[Any], Any]())
      try snapshot()
      finally Vol.context.remove()
    } else if (existing.containsKey(this)) {
      existing.get(this).asInstanceOf[T]
    } else {
      val value = eval()
      existing.put(this, value)
      value
    }
  }

  def map[U](f: T => U): Vol[U] = Vol.Mapped(this, f)

  def flatMap[U](f: T => Vol[U]): Vol[U] = Vol.FlatMapped(this, f)

}

object Vol {

  private val context: ThreadLocal[IdentityHashMap[Vol[Any], Any]] =
    new ThreadLocal()

  case class AtomicRef[T](value: AtomicReference[T]) extends Vol[T] {
    override protected def eval(): T = value.get()
  }

  case class Function[T](f: () => T) extends Vol[T] {
    override protected def eval(): T = f()
  }

  private case class Mapped[T, U](source: Vol[T], f: T => U) extends Vol[U] {
    override protected def eval(): U = f(source.snapshot())
  }

  private case class FlatMapped[T, U](source: Vol[T], f: T => Vol[U])
      extends Vol[U] {

    override protected def eval(): U = f(source.snapshot()).snapshot()
  }
}
