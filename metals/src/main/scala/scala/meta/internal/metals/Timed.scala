package scala.meta.internal.metals

case class Timed[T](value: T, timer: Timer)
object Timed {
  def apply[T](value: T, time: Time): Timed[T] = Timed(value, new Timer(time))
}
