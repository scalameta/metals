package scala.meta.internal.metals

import ch.epfl.scala.{bsp4j => b}
import scala.util.Failure
import scala.util.Success
import scala.util.Try

class BuildTargetClassesFinder(
    buildTargets: BuildTargets,
    buildTargetClasses: BuildTargetClasses
) {

  //In case of success returns non-empty list
  def findMainClassAndItsBuildTarget(
      className: String,
      buildTarget: Option[String]
  ): Try[List[(b.ScalaMainClass, b.BuildTarget)]] =
    buildTarget.fold {
      val classes =
        buildTargetClasses.findMainClassByName(className).collect {
          case (clazz, BuildTargetIdOf(buildTarget)) => (clazz, buildTarget)
        }
      if (classes.nonEmpty) Success(classes)
      else
        Failure(new ClassNotFoundException(className))
    } { targetName =>
      buildTargets
        .findByDisplayName(targetName)
        .fold[Try[List[(b.ScalaMainClass, b.BuildTarget)]]] {
          Failure(
            new BuildTargetNotFoundException(targetName)
          )
        } { target =>
          buildTargetClasses
            .classesOf(target.getId())
            .mainClasses
            .values
            .find(
              _.getClassName == className
            )
            .fold[Try[List[(b.ScalaMainClass, b.BuildTarget)]]] {
              Failure(
                new ClassNotFoundInBuildTargetException(
                  className,
                  targetName
                )
              )
            } { clazz => Success(List(clazz -> target)) }
        }
    }

  //In case of success returns non-empty list
  def findTestClassAndItsBuildTarget(
      className: String,
      buildTarget: Option[String]
  ): Try[List[(String, b.BuildTarget)]] =
    buildTarget.fold {
      val classes =
        buildTargetClasses.findTestClassByName(className).collect {
          case (clazz, BuildTargetIdOf(buildTarget)) => (clazz, buildTarget)
        }
      if (classes.nonEmpty) Success(classes)
      else
        Failure(new ClassNotFoundException(className))
    } { targetName =>
      buildTargets
        .findByDisplayName(targetName)
        .fold[Try[List[(String, b.BuildTarget)]]] {
          Failure(
            new BuildTargetNotFoundException(targetName)
          )
        } { target =>
          buildTargetClasses
            .classesOf(target.getId())
            .testClasses
            .values
            .find(
              _ == className
            )
            .fold[Try[List[(String, b.BuildTarget)]]] {
              Failure(
                new ClassNotFoundInBuildTargetException(
                  className,
                  targetName
                )
              )
            } { clazz =>
              Success(
                List(clazz -> target)
              )
            }
        }
    }

  object BuildTargetIdOf {
    def unapply(id: b.BuildTargetIdentifier): Option[b.BuildTarget] = {
      buildTargets.info(id)
    }
  }

}

class BuildTargetNotFoundException(buildTargetName: String)
    extends Exception(s"Build target not found: $buildTargetName")

class ClassNotFoundInBuildTargetException(
    className: String,
    buildTargetName: String
) extends Exception(
      s"Class '$className' not found in build target '$buildTargetName'"
    )
