package scala.meta.internal.metals

import ch.epfl.scala.{bsp4j => b}
import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.semanticdb.Scala.Descriptor
import scala.meta.internal.semanticdb.Scala.Symbols

/**
 * In-memory index of main class symbols grouped by their enclosing build target
 */
final class BuildTargetClasses(
    buildServer: () => Option[BuildServerConnection]
)(implicit val ec: ExecutionContext) {
  private val index = new TrieMap[b.BuildTargetIdentifier, Classes]()

  def main(target: b.BuildTargetIdentifier): TrieMap[String, b.ScalaMainClass] =
    classesOf(target).main

  val onCompiled: BatchedFunction[b.BuildTargetIdentifier, Unit] =
    BatchedFunction.fromFuture(fetchMainClassesFor)

  private def fetchMainClassesFor(
      targets: Seq[b.BuildTargetIdentifier]
  ): Future[Unit] = {
    targets.foreach { target =>
      classesOf(target).clear()
    }

    buildServer() match {
      case Some(connection) =>
        val parameters = new b.ScalaMainClassesParams(targets.asJava)
        val task = for {
          result <- connection.mainClasses(parameters).asScala
          _ = cacheMainClasses(result)
        } yield ()

        task
      case None =>
        Future.successful(())
    }
  }

  private def classesOf(target: b.BuildTargetIdentifier): Classes =
    index.getOrElseUpdate(target, new Classes)

  private def cacheMainClasses(result: b.ScalaMainClassesResult): Unit = {
    def createObjectSymbol(className: String): String = {
      val symbol = className.replaceAll("\\.", "/")
      val isInsideEmptyPackage = !className.contains(".")
      if (isInsideEmptyPackage) {
        Symbols.Global(Symbols.EmptyPackage, Descriptor.Term(symbol))
      } else {
        symbol
      }
    }

    for {
      item <- result.getItems.asScala
      target = item.getTarget
      aClass <- item.getClasses.asScala
      objectSymbol = createObjectSymbol(aClass.getClassName)
    } classesOf(target).main.put(objectSymbol, aClass)
  }

  final class Classes {
    val main = new TrieMap[String, b.ScalaMainClass]()

    def clear(): Unit = {
      main.clear()
    }
  }
}
