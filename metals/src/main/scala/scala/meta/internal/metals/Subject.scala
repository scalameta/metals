package scala.meta.internal.metals

trait Observable[T] {
  def subscribe(fn: T => Unit): Unit
}

final class Subject[T] extends Observable[T] {
  private var subscribers: List[T => Unit] = Nil
  def subscribe(fn: T => Unit): Unit =
    subscribers = fn :: subscribers
  def push(value: T): Unit = subscribers.foreach(_.apply(value))
}
