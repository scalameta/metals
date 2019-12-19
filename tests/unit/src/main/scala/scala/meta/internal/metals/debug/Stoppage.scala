package scala.meta.internal.metals.debug

import scala.concurrent.Future

final case class Stoppage(frame: StackFrame, cause: Stoppage.Cause)

object Stoppage {

  sealed trait Cause
  object Cause {
    case object Breakpoint extends Cause
    case object Step extends Cause
    case class Other(name: String) extends Cause
  }

  trait Handler {
    def apply(stoppage: Stoppage): Future[DebugStep]
    def shutdown: Future[Unit]
  }

  object Handler {
    case object Continue extends Handler {
      override def apply(stoppage: Stoppage): Future[DebugStep] =
        Future.successful(DebugStep.Continue)
      override def shutdown: Future[Unit] = Future.unit
    }
  }
}
