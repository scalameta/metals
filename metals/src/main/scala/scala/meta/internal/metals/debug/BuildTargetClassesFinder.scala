package scala.meta.internal.metals.debug

import scala.collection.JavaConverters._

import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.mtags.OnDemandSymbolIndex
import scala.meta.internal.mtags.Symbol
import scala.meta.internal.semanticdb.Scala.Descriptor
import scala.meta.io.AbsolutePath

import cats.data.NonEmptyList
import ch.epfl.scala.bsp4j.BuildTarget
import ch.epfl.scala.{bsp4j => b}

class BuildTargetClassesFinder(
    buildTargets: BuildTargets,
    buildTargetClasses: BuildTargetClasses,
    index: OnDemandSymbolIndex,
) {

  def findMainClassAndItsBuildTarget(
      className: String,
      buildTarget: Option[BuildTarget],
  ): Either[NoClassError, NonEmptyList[
    (b.ScalaMainClass, b.BuildTarget)
  ]] = {
    val fromBuildTargets = findClassAndBuildTarget[b.ScalaMainClass](
      className = className,
      buildTarget = buildTarget,
      findClassesByName = buildTargetClasses.findMainClassByName(_),
      classesByBuildTarget = buildTargetClasses
        .classesOf(_)
        .mainClasses
        .values,
      getClassName = { clazz => clazz.getClassName },
    )

    fromBuildTargets.left.flatMap { error =>
      val found = error match {
        // We check whether there is a main in dependencies that is not reported via BSP
        case ClassNotFoundInBuildTargetException(className, target) =>
          revertToDependencies(className, Some(target))
        case _: ClassNotFound =>
          revertToDependencies(className, buildTarget = None)
        case _ => Nil
      }
      found match {
        case Nil => Left(error)
        case head :: tail => Right(NonEmptyList(head, tail))
      }
    }
  }

  /**
   * For a given className and name of build target find and return matching results.
   */
  def findTestClassAndItsBuildTarget(
      className: String,
      buildTarget: Option[BuildTarget],
  ): Either[NoClassError, NonEmptyList[(String, b.BuildTarget)]] =
    findClassAndBuildTarget[String](
      className,
      buildTarget,
      buildTargetClasses.findTestClassByName(_),
      id =>
        buildTargetClasses
          .classesOf(id)
          .testClasses
          .values
          .map(_.fullyQualifiedName),
      clazz => clazz,
    )

  private def revertToDependencies(
      className: String,
      buildTarget: Option[BuildTarget],
  ): List[(b.ScalaMainClass, BuildTarget)] = {

    def findTarget(path: AbsolutePath) =
      for {
        targetId <- buildTargets.inferBuildTarget(path)
        target <- buildTargets.info(targetId)
      } yield target

    for {
      symbol <- buildTargetClasses.symbolFromClassName(
        className,
        List(Descriptor.Term, Descriptor.Type),
      )
      cls <- index.definition(Symbol(symbol))
      target <- buildTarget.orElse(findTarget(cls.path))
    } yield (
      new b.ScalaMainClass(className, Nil.asJava, Nil.asJava),
      target,
    )
  }

  private def findClassAndBuildTarget[A](
      className: String,
      buildTarget: Option[BuildTarget],
      findClassesByName: String => List[(A, b.BuildTargetIdentifier)],
      classesByBuildTarget: b.BuildTargetIdentifier => Iterable[A],
      getClassName: A => String,
  ): Either[NoClassError, NonEmptyList[(A, b.BuildTarget)]] =
    buildTarget match {
      case None =>
        val classes = findClassesByName(className)
          .collect { case (clazz, BuildTargetIdOf(buildTarget)) =>
            (clazz, buildTarget)
          }
          .sortBy { case (_, target) =>
            buildTargets.buildTargetsOrder(target.getId())
          }
          .reverse
        classes match {
          case head :: tail => Right(NonEmptyList(head, tail))
          case Nil => Left(ClassNotFound(className))
        }
      case Some(target) =>
        classesByBuildTarget(target.getId)
          .find(clazz => getClassName(clazz) == className)
          .toRight(ClassNotFoundInBuildTargetException(className, target))
          .map(clazz => NonEmptyList.of(clazz -> target))
    }

  object BuildTargetIdOf {
    def unapply(id: b.BuildTargetIdentifier): Option[b.BuildTarget] = {
      buildTargets.info(id)
    }
  }

}
