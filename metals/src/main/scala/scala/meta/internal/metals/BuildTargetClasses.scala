package scala.meta.internal.metals

import java.util
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import ch.epfl.scala.bsp4j.ScalaMainClass
import ch.epfl.scala.{bsp4j => b}
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.meta.internal.metals.MetalsEnrichments._

final class BuildTargetClasses(
    buildServer: () => Option[BuildServerConnection]
)(implicit val ec: ExecutionContext) {
  private val buildTargets =
    new ConcurrentHashMap[b.BuildTargetIdentifier, Classes]()

  def isInitialized(target: b.BuildTargetIdentifier): Boolean =
    classesOf(target).initialized.get()

  def main(target: b.BuildTargetIdentifier): util.Map[String, ScalaMainClass] =
    classesOf(target).main

  val onCompiled: BatchedFunction[b.BuildTargetIdentifier, Unit] =
    BatchedFunction.fromFuture(fetchMainClassesFor)

  private def fetchMainClassesFor(
      targets: Seq[b.BuildTargetIdentifier]
  ): Future[Unit] = {
    for (target <- targets) {
      classesOf(target).clear()
    }

    buildServer() match {
      case Some(connection) =>
        val parameters = new b.ScalaMainClassesParams(targets.asJava)
        val task = for {
          mainClasses <- connection.mainClasses(parameters).asScala
          _ = initializeMainClasses(mainClasses)
        } yield
          for (target <- targets) {
            classesOf(target).initialized.set(true)
          }

        task
      case None =>
        Future.successful(())
    }
  }

  private def classesOf(target: b.BuildTargetIdentifier): Classes =
    buildTargets.computeIfAbsent(target, _ => new Classes)

  private def initializeMainClasses(result: b.ScalaMainClassesResult): Unit = {
    def createObjectSymbol(className: String): String = {
      val isRootPackage = !className.contains(".")
      val symbol = className.replaceAll("\\.", "/") + "."
      scribe.info(s"$className, $isRootPackage, $symbol")
      if (isRootPackage) {
        "_empty_/" + symbol
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
    val initialized = new AtomicReference[Boolean](false)
    val main = new ConcurrentHashMap[String, ScalaMainClass]()

    def clear(): Unit = {
      main.clear()
    }
  }
}
