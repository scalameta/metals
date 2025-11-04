package scala.meta.internal.metals.debug

import scala.jdk.CollectionConverters._
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.debug.DiscoveryFailures._
import scala.meta.internal.mtags.OnDemandSymbolIndex
import scala.meta.internal.mtags.Symbol
import scala.meta.internal.semanticdb.Scala.Descriptor
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTarget
import ch.epfl.scala.{bsp4j => b}

class BuildTargetClassesFinder(
    buildTargets: BuildTargets,
    buildTargetClasses: BuildTargetClasses,
    index: OnDemandSymbolIndex,
) {

  // In case of success returns non-empty list
  def findMainClassAndItsBuildTarget(
      className: String,
      buildTarget: Option[String],
  ): Try[List[(b.ScalaMainClass, b.BuildTarget)]] = {
    findClassAndBuildTarget(
      className,
      buildTarget,
      buildTargetClasses.findMainClassByName(_),
      buildTargetClasses
        .classesOf(_)
        .mainClasses
        .values,
      { (clazz: b.ScalaMainClass) => clazz.getClassName },
    ).recoverWith { case ex =>
      val found = ex match {
        // We check whether there is a main in dependencies that is not reported via BSP
        case ClassNotFoundInBuildTargetException(className, target) =>
          revertToDependencies(
            className,
            buildTargets.findByDisplayNameOrUri(target),
          )
        case _: NoMainClassFoundException =>
          revertToDependencies(className, buildTarget = None)
        case _ => Nil
      }
      found match {
        case Nil => Failure(ex)
        case deps => Success(deps)
      }
    }
  }

  // In case of success returns non-empty list
  def findTestClassAndItsBuildTarget(
      className: String,
      buildTarget: Option[String],
  ): Try[List[(String, b.BuildTarget)]] =
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
        List(Descriptor.Term.apply, Descriptor.Type.apply),
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
      buildTarget: Option[String],
      findClassesByName: String => List[(A, b.BuildTargetIdentifier)],
      classesByBuildTarget: b.BuildTargetIdentifier => Iterable[A],
      getClassName: A => String,
  ): Try[List[(A, b.BuildTarget)]] =
    buildTarget.fold {
      val classes =
        findClassesByName(className)
          .collect { case (clazz, BuildTargetIdOf(buildTarget)) =>
            (clazz, buildTarget)
          }
          .sortBy { case (_, target) =>
            buildTargets.buildTargetsOrder(target.getId())
          }
          .reverse
      if (classes.nonEmpty) Success(classes)
      else Failure(new NoMainClassFoundException(className))
    } { targetName =>
      buildTargets
        .findByDisplayNameOrUri(targetName)
        .orElse(buildTargets.findByDisplayNameOrUri(targetName))
        .fold[Try[List[(A, b.BuildTarget)]]] {
          Failure(
            new BuildTargetNotFoundException(
              targetName,
              buildTargets.all.map(_.getDisplayName()).toList,
            )
          )
        } { target =>
          classesByBuildTarget(target.getId())
            .find(
              getClassName(_) == className
            )
            .fold[Try[List[(A, b.BuildTarget)]]] {
              Failure(
                DiscoveryFailures.ClassNotFoundInBuildTargetException(
                  className,
                  target.getDisplayName(),
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
