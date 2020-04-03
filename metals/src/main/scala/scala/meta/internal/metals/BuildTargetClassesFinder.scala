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
    findClassAndBuildTarget(
      className,
      buildTarget,
      buildTargetClasses.findMainClassByName(_),
      buildTargetClasses
        .classesOf(_)
        .mainClasses
        .values,
      { clazz: b.ScalaMainClass => clazz.getClassName }
    )

  //In case of success returns non-empty list
  def findTestClassAndItsBuildTarget(
      className: String,
      buildTarget: Option[String]
  ): Try[List[(String, b.BuildTarget)]] =
    findClassAndBuildTarget(
      className,
      buildTarget,
      buildTargetClasses.findTestClassByName(_),
      buildTargetClasses.classesOf(_).testClasses.values,
      { clazz: String => clazz }
    )

  private def findClassAndBuildTarget[A](
      className: String,
      buildTarget: Option[String],
      findClassesByName: String => List[(A, b.BuildTargetIdentifier)],
      classesByBuildTarget: b.BuildTargetIdentifier => Iterable[A],
      getClassName: A => String
  ): Try[List[(A, b.BuildTarget)]] =
    buildTarget.fold {
      val classes =
        findClassesByName(className).collect {
          case (clazz, BuildTargetIdOf(buildTarget)) => (clazz, buildTarget)
        }
      if (classes.nonEmpty) Success(classes)
      else
        Failure(new ClassNotFoundException(className))
    } { targetName =>
      buildTargets
        .findByDisplayName(targetName)
        .fold[Try[List[(A, b.BuildTarget)]]] {
          Failure(
            new BuildTargetNotFoundException(targetName)
          )
        } { target =>
          classesByBuildTarget(target.getId())
            .find(
              getClassName(_) == className
            )
            .fold[Try[List[(A, b.BuildTarget)]]] {
              Failure(
                ClassNotFoundInBuildTargetException(
                  className,
                  target
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

case class ClassNotFoundInBuildTargetException(
    className: String,
    buildTarget: b.BuildTarget
) extends Exception(
      s"Class '$className' not found in build target '${buildTarget.getDisplayName()}'"
    )
