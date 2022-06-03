package scala.meta.internal.metals.debug

import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.BatchedFunction
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.MetalsEnrichments.given
import scala.meta.internal.metals.debug.BuildTargetClasses.Classes
import scala.meta.internal.metals.debug.BuildTargetClasses.TestSymbolInfo
import scala.meta.internal.semanticdb.Scala.Descriptor
import scala.meta.internal.semanticdb.Scala.Symbols

import ch.epfl.scala.{bsp4j => b}

/**
 * In-memory index of main class symbols grouped by their enclosing build target
 */
final class BuildTargetClasses(buildTargets: BuildTargets)(implicit
    val ec: ExecutionContext
) {
  private val index = TrieMap.empty[b.BuildTargetIdentifier, Classes]
  private val jvmRunEnvironments
      : TrieMap[b.BuildTargetIdentifier, b.JvmEnvironmentItem] =
    TrieMap.empty[b.BuildTargetIdentifier, b.JvmEnvironmentItem]
  val rebuildIndex: BatchedFunction[b.BuildTargetIdentifier, Unit] =
    BatchedFunction.fromFuture(
      fetchClasses,
      "buildTargetClasses",
      default = Some(()),
    )

  def classesOf(target: b.BuildTargetIdentifier): Classes = {
    index.getOrElse(target, new Classes)
  }

  def invalidate(target: b.BuildTargetIdentifier): Unit = {
    index.put(target, new Classes)
  }

  def clear(): Unit = {
    jvmRunEnvironments.clear()
  }

  def findMainClassByName(
      name: String
  ): List[(b.ScalaMainClass, b.BuildTargetIdentifier)] =
    findClassesBy(_.mainClasses.values.find(_.getClassName() == name))

  def findTestClassByName(
      name: String
  ): List[(String, b.BuildTargetIdentifier)] =
    findClassesBy(
      _.testClasses.values
        .find(_.fullyQualifiedName == name)
        .map(_.fullyQualifiedName)
    )

  def getTestClasses(
      name: String,
      id: b.BuildTargetIdentifier,
  ): List[(String, TestSymbolInfo)] = {
    index.get(id).toList.flatMap {
      _.testClasses
        .filter { case (_, info) =>
          info.fullyQualifiedName == name
        }
        .toList
    }
  }

  private def findClassesBy[A](
      f: Classes => Option[A]
  ): List[(A, b.BuildTargetIdentifier)] = {
    index.view
      .mapValues(f)
      .toList
      .collect { case (target, Some(clazz)) =>
        clazz -> target
      }
  }

  private def fetchClasses(
      targets: Seq[b.BuildTargetIdentifier]
  ): Future[Unit] = {
    val distinctTargets = targets.distinct
    Future
      .traverse(distinctTargets.groupBy(buildTargets.buildServerOf).toSeq) {
        case (None, _) =>
          Future.successful(())
        case (Some(connection), targets0) =>
          val targetsList = targets0.asJava
          val classes = targets0.map(t => (t, new Classes)).toMap

          val updateMainClasses = connection
            .mainClasses(new b.ScalaMainClassesParams(targetsList))
            .map(cacheMainClasses(classes, _))

          val updateTestClasses =
            connection
              .testClasses(new b.ScalaTestClassesParams(targetsList))
              .map(cacheTestClasses(classes, _))

          for {
            _ <- updateMainClasses
            _ <- updateTestClasses
          } yield {
            targetsList.forEach(invalidate)
            classes.foreach { case (id, classes) =>
              index.put(id, classes)
            }
          }
      }
      .ignoreValue
  }

  def jvmRunEnvironmentSync(
      buildTargetId: b.BuildTargetIdentifier
  ): Option[b.JvmEnvironmentItem] = jvmRunEnvironments.get(buildTargetId)

  def jvmRunEnvironment(
      buildTargetId: b.BuildTargetIdentifier
  ): Future[Option[b.JvmEnvironmentItem]] = {
    jvmRunEnvironments.get(buildTargetId) match {
      case None =>
        buildTargets.buildServerOf(buildTargetId) match {
          case None => Future.successful(None)
          case Some(connection) =>
            connection
              .jvmRunEnvironment(
                new b.JvmRunEnvironmentParams(List(buildTargetId).asJava)
              )
              .map { env =>
                cacheJvmRunEnvironment(env)
                env.getItems().asScala.headOption
              }

        }
      case jvmRunEnv: Some[b.JvmEnvironmentItem] =>
        Future.successful(jvmRunEnv)
    }
  }

  private def cacheJvmRunEnvironment(
      result: b.JvmRunEnvironmentResult
  ): Unit = {
    for {
      item <- result.getItems().asScala
      target = item.getTarget
    } {
      jvmRunEnvironments.put(target, item)
    }
  }

  private def cacheMainClasses(
      classes: Map[b.BuildTargetIdentifier, Classes],
      result: b.ScalaMainClassesResult,
  ): Unit = {
    for {
      item <- result.getItems.asScala
      target = item.getTarget
      aClass <- item.getClasses.asScala
      descriptors = descriptorsForMainClasses(target)
      symbol <- symbolFromClassName(
        aClass.getClassName,
        descriptors,
      )
    } {
      classes(target).mainClasses.put(symbol, aClass)
    }
  }

  private def cacheTestClasses(
      classes: Map[b.BuildTargetIdentifier, Classes],
      result: b.ScalaTestClassesResult,
  ): Unit = {
    for {
      item <- result.getItems.asScala
      target = item.getTarget
      className <- item.getClasses.asScala
      symbol <-
        symbolFromClassName(
          className,
          List(Descriptor.Term.apply, Descriptor.Type.apply),
        )
    } {
      // item.getFramework() can return null!
      val framework = TestFramework(Option(item.getFramework()))
      val testInfo = BuildTargetClasses.TestSymbolInfo(className, framework)
      classes(target).testClasses.put(symbol, testInfo)
    }
  }

  private def descriptorsForMainClasses(
      buildTarget: b.BuildTargetIdentifier
  ): List[String => Descriptor] = {
    buildTargets.scalaTarget(buildTarget) match {
      case Some(_) =>
        List(Descriptor.Term.apply)
      case None =>
        List(Descriptor.Type.apply)
    }
  }

  def symbolFromClassName(
      className: String,
      descriptors: List[String => Descriptor],
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

  def cancel(): Unit = {
    rebuildIndex.cancelAll()
  }
}

sealed abstract class TestFramework(val canResolveChildren: Boolean) {
  def names: List[String]
}

object TestFramework {
  def apply(framework: Option[String]): TestFramework = framework
    .map {
      case "JUnit" => JUnit4
      case "munit" => MUnit
      case "ScalaTest" => Scalatest
      case "weaver-cats-effect" => WeaverCatsEffect
      case _ => Unknown
    }
    .getOrElse(Unknown)
}

case object JUnit4 extends TestFramework(true) {
  def names: List[String] = List("com.novocode.junit.JUnitFramework")
}

case object MUnit extends TestFramework(true) {
  def names: List[String] = List("munit.Framework")
}

case object Scalatest extends TestFramework(true) {
  def names: List[String] =
    List(
      "org.scalatest.tools.Framework",
      "org.scalatest.tools.ScalaTestFramework",
    )
}

case object WeaverCatsEffect extends TestFramework(true) {
  def names: List[String] = List("weaver.BaseCatsSuite")
}

case object Unknown extends TestFramework(false) {
  def names: List[String] = Nil
}

object BuildTargetClasses {
  type Symbol = String
  type FullyQualifiedClassName = String

  final case class TestSymbolInfo(
      fullyQualifiedName: FullyQualifiedClassName,
      framework: TestFramework,
  )
  final class Classes {
    val mainClasses = new TrieMap[Symbol, b.ScalaMainClass]()
    val testClasses = new TrieMap[Symbol, TestSymbolInfo]()

    def isEmpty: Boolean = mainClasses.isEmpty && testClasses.isEmpty
  }
}
