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
        else (info.getSource.getPath, info.getLine)

      val (expected, nextStep) = expectedSteps.dequeue()

      if (
        actualLine == expected.line && expected.pathEqualsActual(actualPath)
      ) {
        if (actualPath.toAbsolutePath.exists) {
          nextStep
        } else {
          throw new Exception(s"${expected.file} does not exist")
        }
      } else {
        val error =
          s"Obtained [$actualPath, $actualLine], expected [${expected.file}, ${expected.line}]"
        throw new Exception(error)
      }
    }
  }

  def at(path: String, line: Int)(nextStep: DebugStep): StepNavigator = {
    val location = WorkspaceLocation(root, path, line)
    at(location)(nextStep)
  }

  def atDependency(path: String, line: Int)(
      nextStep: DebugStep
  ): StepNavigator = {
    val location = DependencyLocation(dependencies, path, line)
    at(location)(nextStep)
  }

  private def at(location: Location)(
      nextStep: DebugStep
  ): StepNavigator = {
    new StepNavigator(root, dependencies, steps :+ (location -> nextStep))
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

  sealed trait Location {
    def pathEqualsActual(actual: String): Boolean
    def file: String
    def line: Long
  }
  case class WorkspaceLocation(root: AbsolutePath, path: String, lineNum: Long)
      extends Location {

    override def pathEqualsActual(actual: String): Boolean =
      actual.stripPrefix("file://") == file.toString
    override def file: String = root.resolve(path).toString
    override def line: Long = lineNum
    override def toString: String = s"$root$file:$line"
  }
  case class DependencyLocation(
      dependencies: AbsolutePath,
      path: String,
      lineNum: Long
  ) extends Location {

    override def pathEqualsActual(actual: String): Boolean = {
      if (actual.startsWith("jar:"))
        actual.endsWith(path)
      else
        actual.stripPrefix("file://") == file.toString
    }
    override def file: String = dependencies.resolve(path).toString
    override def line: Long = lineNum
    override def toString: String = s"$dependencies$file:$line"
  }
}
