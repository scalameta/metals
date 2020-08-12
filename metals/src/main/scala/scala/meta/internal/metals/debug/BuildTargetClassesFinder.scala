package scala.meta.internal.metals.debug

import scala.collection.JavaConverters._
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.mtags.OnDemandSymbolIndex
import scala.meta.internal.mtags.Symbol
import scala.meta.internal.semanticdb.Scala.Descriptor
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTarget
import ch.epfl.scala.{bsp4j => b}

class BuildTargetClassesFinder(
    buildTargets: BuildTargets,
    buildTargetClasses: BuildTargetClasses,
    index: OnDemandSymbolIndex
) {

  //In case of success returns non-empty list
  def findMainClassAndItsBuildTarget(
      className: String,
      buildTarget: Option[String]
  ): Try[List[(b.ScalaMainClass, b.BuildTarget)]] = {
    findClassAndBuildTarget(
      className,
      buildTarget,
      buildTargetClasses.findMainClassByName(_),
      buildTargetClasses
        .classesOf(_)
        .mainClasses
        .values,
      { clazz: b.ScalaMainClass => clazz.getClassName }
    ).recoverWith {
      case ex =>
        val found = ex match {
          // We check whether there is a main in dependencies that is not reported via BSP
          case ClassNotFoundInBuildTargetException(className, target) =>
            revertToDependencies(className, Some(target))
          case _: ClassNotFoundException =>
            revertToDependencies(className, buildTarget = None)
        }
        found match {
          case Nil => Failure(ex)
          case deps => Success(deps)
        }
    }
  }

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

  private def revertToDependencies(
      className: String,
      buildTarget: Option[BuildTarget]
  ): List[(b.ScalaMainClass, BuildTarget)] = {

    def findTarget(path: AbsolutePath) =
      for {
        targetId <- buildTargets.inferBuildTarget(path)
        target <- buildTargets.info(targetId)
      } yield target

    for {
      symbol <- buildTargetClasses.symbolFromClassName(
        className,
        List(Descriptor.Term, Descriptor.Type)
      )
      cls <- index.definition(Symbol(symbol))
      target <- buildTarget.orElse(findTarget(cls.path))
    } yield (
      new b.ScalaMainClass(className, Nil.asJava, Nil.asJava),
      target
    )
  }

  private def findClassAndBuildTarget[A](
      className: String,
      buildTarget: Option[String],
      findClassesByName: String => List[(A, b.BuildTargetIdentifier)],
      classesByBuildTarget: b.BuildTargetIdentifier => Iterable[A],
      getClassName: A => String
  ): Try[List[(A, b.BuildTarget)]] =
    buildTarget.fold {
      val classes =
        findClassesByName(className)
          .collect {
            case (clazz, BuildTargetIdOf(buildTarget)) => (clazz, buildTarget)
          }
          .sortBy {
            case (_, target) => buildTargets.buildTargetsOrder(target.getId())
          }
          .reverse
      if (classes.nonEmpty) Success(classes)
      else Failure(new ClassNotFoundException(className))
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

case class BuildTargetNotFoundException(buildTargetName: String)
    extends Exception(s"Build target not found: $buildTargetName")

case class ClassNotFoundInBuildTargetException(
    className: String,
    buildTarget: b.BuildTarget
) extends Exception(
      s"Class '$className' not found in build target '${buildTarget.getDisplayName()}'"
    )
