package scala.meta.internal.metals.debug

import scala.meta.io.AbsolutePath

import ch.epfl.scala.{bsp4j => b}

sealed abstract class DebugProviderError(val msg: String) extends Exception(msg)
case class BuildTargetNotFoundException(buildTargetName: String)
    extends DebugProviderError(s"Build target not found: $buildTargetName")

sealed abstract class NoClassError(msg: String) extends DebugProviderError(msg)

case class ClassNotFoundInBuildTargetException(
    className: String,
    buildTarget: b.BuildTarget,
) extends NoClassError(
      s"Class '$className' not found in build target '${buildTarget.getDisplayName()}'"
    )

case class ClassNotFound(className: String)
    extends NoClassError(s"There is no $className class")

case class BuildTargetUndefinedException()
    extends DebugProviderError(
      "Debugger configuration is missing 'buildTarget' param."
    )

case class BuildTargetNotFoundForPathException(path: AbsolutePath)
    extends DebugProviderError(
      s"No build target could be found for the path: ${path.toString()}"
    )

case class BuildTargetContainsNoMainException(buildTargetName: String)
    extends DebugProviderError(
      s"No main could be found in build target: $buildTargetName"
    )

case class NoTestsFoundException(
    testType: String,
    name: String,
) extends DebugProviderError(
      s"No tests could be found in ${testType}: $name"
    )

case object WorkspaceErrorsException
    extends DebugProviderError(
      s"Cannot run class, since the workspace has errors."
    )

case object NoRunOptionException
    extends DebugProviderError(
      s"There is nothing to run or test in the current file."
    )

case object SemanticDbNotFoundException
    extends DebugProviderError(
      "Build misconfiguration. No semanticdb can be found for you file, please check the doctor."
    )

case class InvalidEnvFileException(path: AbsolutePath)
    extends DebugProviderError(
      s"Unable to open the specified .env file ${path.toString()}. " +
        "Please make sure the file exists and is readable."
    )

case class UnknownRunTypeException(runType: String)
    extends DebugProviderError(s"Received invalid runType: ${runType}.")
