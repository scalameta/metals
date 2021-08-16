package scala.meta.internal.metals.debug

import scala.collection.mutable
import scala.concurrent.Future

import scala.meta.internal.metals.Directories
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.debug.StepNavigator._
import scala.meta.io.AbsolutePath

final class StepNavigator(
    root: AbsolutePath,
    dependencies: AbsolutePath,
    steps: Seq[(Location, DebugStep)]
) extends Stoppage.Handler {
  private val expectedSteps = mutable.Queue(steps: _*)

  override def apply(stoppage: Stoppage): DebugStep = {
    if (expectedSteps.isEmpty) {
      val error = s"Unexpected ${stoppage.cause} stoppage at ${stoppage.frame}"
      throw new IllegalStateException(error)
    } else {
      val info = stoppage.frame.info
      val (actualPath, actualLine) =
        if (info.getSource == null) (null, info.getLine)
        else (info.getSource.getPath.toAbsolutePath, info.getLine)

      val (expected, nextStep) = expectedSteps.dequeue()

      if (actualLine == expected.line && actualPath == expected.file) {
        nextStep
      } else {
        val error =
          s"Obtained [$actualPath, $actualLine], expected [${expected.file}, ${expected.line}]"
        throw new Exception(error)
      }
    }
  }

  def at(path: String, line: Int)(nextStep: DebugStep): StepNavigator = {
    at(root.resolve(path), line)(nextStep)
  }

  private def at(path: AbsolutePath, line: Int)(
      nextStep: DebugStep
  ): StepNavigator = {
    val location = Location(path, line)
    new StepNavigator(root, dependencies, steps :+ (location -> nextStep))
  }

  def atDependency(path: String, line: Int)(
      nextStep: DebugStep
  ): StepNavigator = {
    at(dependencies.resolve(path), line)(nextStep)
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
  def apply(root: AbsolutePath): StepNavigator =
    new StepNavigator(root, root.resolve(Directories.dependencies), Nil)

  case class Location(file: AbsolutePath, line: Long) {
    override def toString: String = s"$file:$line"
  }

}
