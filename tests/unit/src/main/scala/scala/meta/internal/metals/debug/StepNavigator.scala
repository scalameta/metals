package scala.meta.internal.metals.debug

import scala.collection.mutable
import scala.concurrent.Future
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.debug.StepNavigator.Location
import scala.meta.io.AbsolutePath

final class StepNavigator(root: AbsolutePath, steps: Seq[(Location, DebugStep)])
    extends Stoppage.Handler {
  private val expectedSteps = mutable.Queue(steps: _*)

  override def apply(stoppage: Stoppage): Future[DebugStep] = {
    if (expectedSteps.isEmpty) {
      val error = s"Unexpected ${stoppage.cause} stoppage at ${stoppage.frame}"
      Future.failed(new IllegalStateException(error))
    } else {
      val info = stoppage.frame.info
      val actualLocation =
        if (info.getSource == null) Location(null, info.getLine)
        else Location(info.getSource.getPath.toAbsolutePath, info.getLine)

      val (expectedLocation, nextStep) = expectedSteps.dequeue()

      if (actualLocation == expectedLocation) {
        Future.successful(nextStep)
      } else {
        val error = s"Obtained [$actualLocation], expected [$expectedLocation]"
        Future.failed(new Exception(error))
      }
    }
  }

  def at(path: String, line: Int)(nextStep: DebugStep): StepNavigator = {
    at(root.resolve(path), line)(nextStep)
  }

  def at(path: AbsolutePath, line: Int)(nextStep: DebugStep): StepNavigator = {
    val location = Location(path, line)
    new StepNavigator(root, steps :+ (location -> nextStep))
  }

  override def shutdown: Future[Unit] = {
    if (expectedSteps.isEmpty) Future.unit
    else {
      val remainingSteps = expectedSteps.map(_._1).mkString("\n")
      Future.failed(
        new IllegalStateException(s"Not all steps reached:\n $remainingSteps")
      )
    }
  }
}

object StepNavigator {
  def apply(root: AbsolutePath): StepNavigator = new StepNavigator(root, Nil)

  case class Location(file: AbsolutePath, line: Long) {
    override def toString: String = s"$file:$line"
  }
}
