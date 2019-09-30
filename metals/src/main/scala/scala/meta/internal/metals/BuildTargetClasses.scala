package scala.meta.internal.metals

import ch.epfl.scala.{bsp4j => b}
import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.meta.internal.metals.BuildTargetClasses.Classes
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.semanticdb.Scala.Descriptor
import scala.meta.internal.semanticdb.Scala.Symbols

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

  def invalidate(target: b.BuildTargetIdentifier): Unit = {
    classesOf(target).invalidate()
  }

  private def fetchClasses(
      targets: Seq[b.BuildTargetIdentifier]
  ): Future[Unit] = {
    buildServer() match {
      case Some(connection) =>
        val targetsList = targets.asJava

        targetsList.forEach(invalidate)

        val updateMainClasses = connection
          .mainClasses(new b.ScalaMainClassesParams(targetsList))
          .asScala
          .map(cacheMainClasses)

        val updateTestClasses = connection
          .testClasses(new b.ScalaTestClassesParams(targetsList))
          .asScala
          .map(cacheTestClasses)

        for {
          _ <- updateMainClasses
          _ <- updateTestClasses
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
    } {
      classesOf(target).mainClasses.put(objectSymbol, aClass)
    }
  }

  private def cacheTestClasses(result: b.ScalaTestClassesResult): Unit = {
    for {
      item <- result.getItems.asScala
      target = item.getTarget
      className <- item.getClasses.asScala
      objectSymbol = createObjectSymbol(className)
    } {
      classesOf(target).testClasses.put(objectSymbol, className)
    }
  }

  private def createObjectSymbol(className: String): String = {
    import scala.reflect.NameTransformer
    className.split("\\.").foldLeft(Symbols.EmptyPackage) { (owner, name) =>
      Symbols.Global(owner, Descriptor.Term(NameTransformer.decode(name)))
    }
  }
}

object BuildTargetClasses {
  final class Classes {
    val mainClasses = new TrieMap[String, b.ScalaMainClass]()
    val testClasses = new TrieMap[String, String]()

    def invalidate(): Unit = {
      mainClasses.clear()
      testClasses.clear()
    }
  }
}
