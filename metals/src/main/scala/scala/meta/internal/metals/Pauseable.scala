package scala.meta.internal.metals

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Interface for something that can be paused and unpaused, for example a stream of compile requests.
 */
trait Pauseable {
  final val isPaused = new AtomicBoolean(false)

  final def pause(): Unit = {
    isPaused.set(true)
    doPause()
  }
  final def unpause(): Unit = {
    isPaused.set(false)
    doUnpause()
  }

  def doPause(): Unit = {}
  def doUnpause(): Unit = {}
}

object Pauseable {

  /**
   * Merges a list of Pausables into a single Pauseable. */
  def fromPausables(all: Iterable[Pauseable]): Pauseable =
    new Pauseable {
      override def doPause(): Unit = Cancelable.cancelEach(all)(_.pause())
      override def doUnpause(): Unit = Cancelable.cancelEach(all)(_.unpause())
    }

}
