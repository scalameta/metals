package scala.meta.internal.metals

trait Subject[S] {
  private var observers: List[S => Unit] = Nil
  def addObserver(observer: S => Unit): Unit = observers = observer :: observers
  def notifyObservers(value: S): Unit = observers.foreach(_.apply(value))
}
