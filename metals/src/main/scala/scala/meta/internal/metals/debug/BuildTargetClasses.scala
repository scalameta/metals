package scala.meta.internal.metals.debug

import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.BatchedFunction
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ScalaVersions
import scala.meta.internal.metals.debug.BuildTargetClasses.Classes
import scala.meta.internal.semanticdb.Scala.Descriptor
import scala.meta.internal.semanticdb.Scala.Symbols

import ch.epfl.scala.{bsp4j => b}

/**
 * In-memory index of main class symbols grouped by their enclosing build target
 */
final class BuildTargetClasses(
    buildTargets: BuildTargets
)(implicit val ec: ExecutionContext) {
  private val index = TrieMap.empty[b.BuildTargetIdentifier, Classes]

  val rebuildIndex: BatchedFunction[b.BuildTargetIdentifier, Unit] =
    BatchedFunction.fromFuture(fetchClasses)

  def classesOf(target: b.BuildTargetIdentifier): Classes = {
    index.getOrElse(target, new Classes)
  }

  def invalidate(target: b.BuildTargetIdentifier): Unit = {
    index.put(target, new Classes)
  }

  def findMainClassByName(
      name: String
  ): List[(b.ScalaMainClass, b.BuildTargetIdentifier)] =
    findClassesBy(_.mainClasses.values.find(_.getClassName() == name))

  def findTestClassByName(
      name: String
  ): List[(String, b.BuildTargetIdentifier)] =
    findClassesBy(_.testClasses.values.find(_ == name))

  private def findClassesBy[A](
      f: Classes => Option[A]
  ): List[(A, b.BuildTargetIdentifier)] = {
    index
      .mapValues(f)
      .toList
      .collect {
        case (target, Some(clazz)) => clazz -> target
      }
  }

  private def fetchClasses(
      targets: Seq[b.BuildTargetIdentifier]
  ): Future[Unit] = {
    Future
      .traverse(targets.groupBy(buildTargets.buildServerOf)) {
        case (None, _) =>
          Future.successful(())
        case (Some(connection), targets0) =>
          val targetsList = targets0.asJava
          targetsList.forEach(invalidate)
          val classes = targets0.map(t => (t, new Classes)).toMap

          val updateMainClasses = connection
            .mainClasses(new b.ScalaMainClassesParams(targetsList))
            .map(cacheMainClasses(classes, _))

          val updateTestClasses = connection
            .testClasses(new b.ScalaTestClassesParams(targetsList))
            .map(cacheTestClasses(classes, _))

          for {
            _ <- updateMainClasses
            _ <- updateTestClasses
          } yield {
            classes.foreach {
              case (id, classes) => index.put(id, classes)
            }
          }
      }
      .ignoreValue
  }

  private def cacheMainClasses(
      classes: Map[b.BuildTargetIdentifier, Classes],
      result: b.ScalaMainClassesResult
  ): Unit = {
    for {
      item <- result.getItems.asScala
      target = item.getTarget
      aClass <- item.getClasses.asScala
      descriptors = descriptorsForMainClasses(target)
      symbol <- symbolFromClassName(
        aClass.getClassName,
        descriptors
      )
    } {
      classes(target).mainClasses.put(symbol, aClass)
    }
  }

  private def cacheTestClasses(
      classes: Map[b.BuildTargetIdentifier, Classes],
      result: b.ScalaTestClassesResult
  ): Unit = {
    for {
      item <- result.getItems.asScala
      target = item.getTarget
      className <- item.getClasses.asScala
      symbol <-
        symbolFromClassName(className, List(Descriptor.Term, Descriptor.Type))
    } {
      classes(target).testClasses.put(symbol, className)
    }
  }

  private def descriptorsForMainClasses(
      buildTarget: b.BuildTargetIdentifier
  ): List[String => Descriptor] = {
    buildTargets.scalaTarget(buildTarget) match {
      case Some(scalaBuildTarget) =>
        if (ScalaVersions.isScala3Version(scalaBuildTarget.scalaVersion))
          List(Descriptor.Term, Descriptor.Type)
        else List(Descriptor.Term)
      case None =>
        List(Descriptor.Type)
    }
  }

  def symbolFromClassName(
      className: String,
      descriptors: List[String => Descriptor]
  ): List[String] = {
    import scala.reflect.NameTransformer
    val isEmptyPackage = !className.contains(".")
    val root =
      if (isEmptyPackage) Symbols.EmptyPackage
      else Symbols.RootPackage
    val names = className.stripSuffix("$").split("\\.")
    val prefix = names.dropRight(1).foldLeft(root) { (owner, name) =>
      Symbols.Global(owner, Descriptor.Package(NameTransformer.decode(name)))
    }
    val name = NameTransformer.decode(names.last)
    descriptors.map(descriptor => Symbols.Global(prefix, descriptor(name)))
  }
}

object BuildTargetClasses {
  final class Classes {
    val mainClasses = new TrieMap[String, b.ScalaMainClass]()
    val testClasses = new TrieMap[String, String]()

    def isEmpty: Boolean = mainClasses.isEmpty && testClasses.isEmpty
  }
}
