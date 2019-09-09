package scala.meta.internal.metals

import ch.epfl.scala.{bsp4j => b}

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}
import scala.meta.internal.metals.BuildTargetClasses.Classes
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.semanticdb.Scala.{Descriptor, Symbols}

/**
 * In-memory index of main class symbols grouped by their enclosing build target
 */
final class BuildTargetClasses(
    buildServer: () => Option[BuildServerConnection]
)(implicit val ec: ExecutionContext) {
  private val index = TrieMap.empty[b.BuildTargetIdentifier, Classes]

  val rebuildIndex: BatchedFunction[b.BuildTargetIdentifier, Unit] =
    BatchedFunction.fromFuture(fetchClasses)

  def classesOf(target: b.BuildTargetIdentifier): Classes = {
    index.getOrElseUpdate(target, new Classes)
  }

  private def fetchClasses(
      targets: Seq[b.BuildTargetIdentifier]
  ): Future[Unit] = {
    buildServer() match {
      case Some(connection) =>
        val targetsList = targets.asJava

        val updateMainClasses = connection
          .mainClasses(new b.ScalaMainClassesParams(targetsList))
          .thenAccept(cacheMainClasses)
          .asScala

        val updateTestSuites = connection
          .testSuites(new b.ScalaTestClassesParams(targetsList))
          .thenAccept(cacheTestSuites)
          .asScala

        for {
          _ <- updateMainClasses
          _ <- updateTestSuites
        } yield ()
      case None =>
        Future.successful(())
    }
  }

  private def cacheMainClasses(result: b.ScalaMainClassesResult): Unit = {
    for {
      item <- result.getItems.asScala
      target = item.getTarget
      aClass <- item.getClasses.asScala
      objectSymbol = createObjectSymbol(aClass.getClassName)
    } classesOf(target).mainClasses.put(objectSymbol, aClass)
  }

  private def cacheTestSuites(result: b.ScalaTestClassesResult): Unit = {
    for {
      item <- result.getItems.asScala
      target = item.getTarget
      className <- item.getClasses.asScala
      objectSymbol = createObjectSymbol(className)
    } classesOf(target).testSuites.put(objectSymbol, className)
  }

  private def createObjectSymbol(className: String): String = {
    val symbol = className.replaceAll("\\.", "/")
    val isInsideEmptyPackage = !className.contains(".")
    if (isInsideEmptyPackage) {
      Symbols.Global(Symbols.EmptyPackage, Descriptor.Term(symbol))
    } else {
      symbol + "."
    }
  }
}

object BuildTargetClasses {
  final class Classes {
    val mainClasses = new TrieMap[String, b.ScalaMainClass]()
    val testSuites = new TrieMap[String, String]()

    def invalidate(): Unit = {
      mainClasses.clear()
      testSuites.clear()
    }
  }
}
