package scala.meta.internal.metals

import java.util.concurrent.CancellationException
import ch.epfl.scala.{bsp4j => b}
import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
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
  private val index = TrieMap.empty[b.BuildTargetIdentifier, Promise[Classes]]

  val rebuildIndex: BatchedFunction[b.BuildTargetIdentifier, Unit] =
    BatchedFunction.fromFuture(fetchClasses)

  def classesOf(target: b.BuildTargetIdentifier): Future[Classes] = {
    index.getOrElseUpdate(target, Promise()).future
  }

  def invalidate(target: b.BuildTargetIdentifier): Unit = {
    val previous = index.put(target, Promise())
    previous.foreach(_.tryFailure(new CancellationException()))
  }

  private def fetchClasses(
      targets: Seq[b.BuildTargetIdentifier]
  ): Future[Unit] = {
    buildServer() match {
      case Some(connection) =>
        val targetsList = targets.asJava
        targetsList.forEach(invalidate)
        val classes = targets.map(t => (t, new Classes)).toMap

        val updateMainClasses = connection
          .mainClasses(new b.ScalaMainClassesParams(targetsList))
          .asScala
          .map(cacheMainClasses(classes, _))

        val updateTestClasses = connection
          .testClasses(new b.ScalaTestClassesParams(targetsList))
          .asScala
          .map(cacheTestClasses(classes, _))

        for {
          _ <- updateMainClasses
          _ <- updateTestClasses
        } yield {
          classes.foreach {
            case (id, classes) =>
              index
                .getOrElseUpdate(id, Promise())
                .success(classes)
          }
        }

      case None =>
        Future.successful(())
    }
  }

  private def cacheMainClasses(
      classes: Map[b.BuildTargetIdentifier, Classes],
      result: b.ScalaMainClassesResult
  ): Unit = {
    for {
      item <- result.getItems.asScala
      target = item.getTarget
      aClass <- item.getClasses.asScala
      symbol <- createSymbols(aClass.getClassName, List(Descriptor.Term))
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
      symbol <- createSymbols(className, List(Descriptor.Term, Descriptor.Type))
    } {
      classes(target).testClasses.put(symbol, className)
    }
  }

  private def createSymbols(
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
