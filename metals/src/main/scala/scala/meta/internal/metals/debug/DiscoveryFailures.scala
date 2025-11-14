package scala.meta.internal.metals.debug

import scala.meta.internal.metals.config.RunType.RunType
import scala.meta.io.AbsolutePath

object DiscoveryFailures {

  case class BuildTargetNotFoundException(
      buildTargetName: String,
      candidates: Seq[String],
  ) extends Exception(
        s"Build target not found: $buildTargetName, candidates:\n${candidates.mkString("\n")}"
      )

  object NothingToRun
      extends Exception(
        "There is nothing to run in the workspace"
      )

  case class ClassNotFoundInBuildTargetException(
      className: String,
      buildTarget: String,
  ) extends Exception(
        s"Main class '$className' not found in build target '${buildTarget}'"
      )

  case class NoMainClassFoundException(
      className: String
  ) extends Exception(
        s"Main class '$className' not found in any build target"
      )

  case class BuildTargetNotFoundForPathException(path: AbsolutePath)
      extends Exception(
        s"No build target could be found for the path: ${path.toString()}"
      )
  case class BuildTargetContainsNoMainException(buildTargetName: String)
      extends Exception(
        s"No main could be found in build target: $buildTargetName"
      )
  case class NoTestsFoundException(
      testType: String,
      name: String,
  ) extends Exception(
        s"No tests could be found in ${testType}: $name"
      )

  case object NoBuildTargetSpecified
      extends Exception(
        s"No build target was specified."
      )

  case class UndefinedPositionException(runType: RunType)
      extends Exception(
        s"Cannot run since the $runType required a position to be defined."
      )
  case object WorkspaceErrorsException
      extends Exception(
        s"Cannot run class, since the workspace has errors."
      )

  case class UndefinedPathException(runType: RunType)
      extends Exception(
        s"Cannot run since the $runType required a path to be defined."
      )
  case object NoRunOptionException
      extends Exception(
        s"There is nothing to run or test in the current file."
      )
  case object SemanticDbNotFoundException
      extends Exception(
        "Build misconfiguration. No semanticdb can be found for you file, please check the doctor."
      )

  case object WorkspaceResetException
      extends Exception(
        "Run cancelled due to workspace reset. Please try running again."
      )
}
