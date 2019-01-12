package scala.meta.internal.metals

import scala.concurrent.Future

case class CancelableFuture[T](
    future: Future[T],
    cancelable: Cancelable = Cancelable.empty
)
