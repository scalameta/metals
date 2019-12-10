package scala.meta.internal.metals.debug

import tests.DiffAssertions
import scala.collection.mutable
import scala.concurrent.Future
import scala.meta.internal.metals.debug.StepNavigator.Location
import scala.meta.io.AbsolutePath
import scala.util.control.NonFatal

final class StepNavigator(root: AbsolutePath, steps: Seq[(Location, DebugStep)])
    extends Stoppage.Handler {
  private val expectedSteps = mutable.Queue(steps: _*)

  override def apply(stoppage: Stoppage): Future[DebugStep] = {
    if (expectedSteps.isEmpty) {
      val error = s"Unexpected ${stoppage.cause} stoppage at ${stoppage.frame}"
      Future.failed(new IllegalStateException(error))
    } else {
      val (expectedLocation, nextStep) = expectedSteps.dequeue()
      try {
        compare(stoppage.frame, expectedLocation)
        Future.successful(nextStep)
      } catch {
        case NonFatal(e) =>
          Future.failed(e)
      }
    }
  }

  private def compare(frame: StackFrame, expected: Location) = {
    val info = frame.info
    val actualLocation = s"${info.getSource.getPath}:${info.getLine}"
    val expectedLocation = s"${expected.file}:${expected.line}"
    DiffAssertions.assertNoDiff(actualLocation, expectedLocation)
  }

  def at(path: String, line: Int)(nextStep: DebugStep): StepNavigator = {
    at(root.resolve(path), line)(nextStep)
  }

  def at(path: AbsolutePath, line: Int)(nextStep: DebugStep): StepNavigator = {
    val location = Location(s"file://$path", line)
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

  case class Location(file: String, line: Int) {
    override def toString: String = s"$file:$line"
  }
}
