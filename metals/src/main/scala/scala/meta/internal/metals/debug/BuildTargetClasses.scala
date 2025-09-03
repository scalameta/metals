package scala.meta.internal.metals.debug

import bloop.config.Config.TestFramework
import ch.epfl.scala.bsp4j as b

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}
import scala.meta.internal.metals.MetalsEnrichments.*
import scala.meta.internal.metals.debug.BuildTargetClasses.{
  Classes,
  TestSymbolInfo,
}
import scala.meta.internal.metals.{
  BatchedFunction,
  BuildTargets,
  SemanticdbFeatureProvider,
}
import scala.meta.internal.semanticdb.Scala.{Descriptor, Symbols}
import scala.meta.internal.semanticdb.{
  ClassSignature,
  TextDocument,
  TextDocuments,
  TypeRef,
}
import scala.meta.io.AbsolutePath

/**
 * In-memory index of main class symbols grouped by their enclosing build target
 */
final class BuildTargetClasses(val buildTargets: BuildTargets)(implicit
    val ec: ExecutionContext
) extends SemanticdbFeatureProvider {
  private val index = TrieMap.empty[b.BuildTargetIdentifier, Classes]

  private val semanticdbCache = TrieMap.empty[AbsolutePath, TextDocuments]

  type JVMRunEnvironmentsMap =
    TrieMap[b.BuildTargetIdentifier, b.JvmEnvironmentItem]

  /** Cache for the 'buildTarget/jvmRunEnvironment' BSP requests */
  private val jvmRunEnvironments: JVMRunEnvironmentsMap = TrieMap.empty

  /** Cache for the 'buildTarget/jvmTestRunEnvironment' BSP requests */
  private val jvmTestRunEnvironments: JVMRunEnvironmentsMap = TrieMap.empty

  def jvmRunEnvironmentsFor(isTests: Boolean): JVMRunEnvironmentsMap =
    if (isTests) jvmTestRunEnvironments else jvmRunEnvironments

  val rebuildIndex: BatchedFunction[b.BuildTargetIdentifier, Unit] =
    BatchedFunction.fromFuture(
      fetchClasses,
      "buildTargetClasses",
      default = Some(()),
    )

  override def onChange(docs: TextDocuments, path: AbsolutePath): Unit = {
    semanticdbCache.put(path, docs)
  }

  override def onDelete(path: AbsolutePath): Unit = {
    semanticdbCache.remove(path)
  }
  override def reset(): Unit = {
    semanticdbCache.clear()
  }

  def classesOf(target: b.BuildTargetIdentifier): Classes = {
    index.getOrElse(target, new Classes)
  }

  def invalidate(target: b.BuildTargetIdentifier): Unit = {
    index.put(target, new Classes)
  }

  def clear(): Unit = {
    jvmRunEnvironments.clear()
    jvmTestRunEnvironments.clear()
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
    index.get(id).toList.flatMap { classes =>
      scribe.debug(
        s"""|Found test classes:
            |${classes.testClasses.values.map(info => s"\t- ${info.fullyQualifiedName}").mkString("\n")}""".stripMargin
      )
      classes.testClasses.filter { case (_, info) =>
        info.fullyQualifiedName == name
      }.toList
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
            if (isBazelBuildAmongTargets(targets0)) {
              Future.successful(
                cacheBazelTestClassesFromSemanticdb(classes, targets0)
              )
            } else {
              connection
                .testClasses(new b.ScalaTestClassesParams(targetsList))
                .map(cacheTestClasses(classes, _))
            }

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
      buildTargetId: b.BuildTargetIdentifier,
      isTests: Boolean,
  ): Option[b.JvmEnvironmentItem] =
    jvmRunEnvironmentsFor(isTests).get(buildTargetId)

  def jvmRunEnvironment(
      buildTargetId: b.BuildTargetIdentifier,
      isTests: Boolean,
  ): Future[Option[b.JvmEnvironmentItem]] = {
    val environments = jvmRunEnvironmentsFor(isTests)

    environments.get(buildTargetId) match {
      case None =>
        scribe.trace(
          s"No cached JVM run environment for build target $buildTargetId (isTests = $isTests)."
        )

        buildTargets.buildServerOf(buildTargetId) match {
          case None =>
            scribe.trace(
              s"No build server connection found for build target $buildTargetId (isTests = $isTests)."
            )
            Future.successful(None)

          case Some(connection) =>
            scribe.trace(
              s"Found build server connection for build target $buildTargetId (isTests = $isTests)."
            )
            val buildTargets = List(buildTargetId)

            def processResult(items: Iterable[b.JvmEnvironmentItem]) = {
              cacheJvmRunEnvironment(items.iterator, environments)
              items.headOption
            }

            if (isTests) {
              connection
                .jvmTestEnvironment(
                  new b.JvmTestEnvironmentParams(buildTargets.asJava)
                )
                .map(env => processResult(env.getItems().asScala))
            } else {
              connection
                .jvmRunEnvironment(
                  new b.JvmRunEnvironmentParams(buildTargets.asJava)
                )
                .map(env => processResult(env.getItems().asScala))
            }

        }

      case jvmRunEnv: Some[b.JvmEnvironmentItem] =>
        scribe.trace(
          s"Found cached JVM run environment for build target $buildTargetId (isTests = $isTests)."
        )
        Future.successful(jvmRunEnv)
    }
  }

  private def cacheJvmRunEnvironment(
      items: Iterator[b.JvmEnvironmentItem],
      environments: JVMRunEnvironmentsMap,
  ): Unit = {
    for {
      item <- items
      target = item.getTarget
    } {
      environments.put(target, item)
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
      val framework = TestFrameworkUtils.from(Option(item.getFramework()))
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

  private def processSemanticdbForTestFrameworkDetection(
      doc: TextDocument,
      classes: Classes,
  ): Unit = {
    doc.symbols.foreach { symbolInfo =>
      symbolInfo.signature match {
        case classSig: ClassSignature =>
          val symbol = symbolInfo.symbol
          val className = symbolToClassName(symbol)
          if (className.nonEmpty) {
            detectTestFramework(classSig, doc) match {
              case Some(framework) =>
                val testInfo = TestSymbolInfo(className, framework)
                classes.testClasses.put(symbol, testInfo)

                scribe.debug(
                  s"Detected test class: $className with framework: ${framework.names.headOption.getOrElse("Unknown")}"
                )
              case None =>
                scribe.debug("Cannot detect test framework")
            }
          }
        case _ =>
      }
    }
  }

  private def detectTestFramework(
      classSig: ClassSignature,
      doc: TextDocument,
  ): Option[TestFramework] = {
    val parentSymbols = extractParentSymbols(classSig)

    parentSymbols.collectFirst { case parentSymbol =>
      findFrameworkRecursively(parentSymbol, doc, visited = Set.empty)
    }.flatten
  }

  private def extractParentSymbols(classSig: ClassSignature): List[String] = {
    classSig.parents.collect { case TypeRef(_, parentSymbol, _) =>
      parentSymbol
    }.toList
  }

  private def findFrameworkRecursively(
      parentSymbol: String,
      doc: TextDocument,
      visited: Set[String],
  ): Option[TestFramework] = {
    if (visited.contains(parentSymbol)) {
      None
    } else {
      TestFrameworkDetector.fromParentSymbol(parentSymbol).orElse {
        doc.symbols.find(_.symbol == parentSymbol).flatMap { parentInfo =>
          parentInfo.signature match {
            case parentClassSig: ClassSignature =>
              val grandParents = extractParentSymbols(parentClassSig)
              val newVisited = visited + parentSymbol

              grandParents.collectFirst { case grandParent =>
                findFrameworkRecursively(grandParent, doc, newVisited)
              }.flatten
            case _ => None
          }
        }
      }
    }
  }

  private def symbolToClassName(symbol: String): String = {
    symbol
      .stripPrefix("_empty_/")
      .stripSuffix("#")
      .replace("/", ".")
  }

  private def isBazelBuildAmongTargets(
      targets: Seq[b.BuildTargetIdentifier]
  ): Boolean = {
    targets.exists { target =>
      val uri = target.getUri
      uri.matches("@//.*?/[^/:]+:[^/:]+$")
    }
  }

  private def cacheBazelTestClassesFromSemanticdb(
      classes: Map[b.BuildTargetIdentifier, Classes],
      targets: Seq[b.BuildTargetIdentifier],
  ): Unit = {
    import scala.jdk.CollectionConverters.*

    targets.foreach { target =>
      val sourceFiles = buildTargets.sourceItemsToBuildTargets
        .filter { case (_, buildTargetIds) =>
          buildTargetIds.asScala.toList.contains(target)
        }
        .map(_._1)
        .filter(_.isScalaFilename)
        .toList

      sourceFiles.foreach { sourcePath =>
        semanticdbCache.get(sourcePath) match {
          case Some(docs) =>
            docs.documents.foreach { doc =>
              processSemanticdbForTestFrameworkDetection(
                doc,
                classes(target),
              )
            }
          case None => ()
        }
      }
    }
  }

}

object TestFrameworkDetector {

  private val frameworkSymbolMap: Map[String, TestFramework] = Map(
    "org/scalatest/flatspec/AnyFlatSpec#" -> TestFramework.ScalaTest,
    "org/scalatest/funspec/AnyFunSpec#" -> TestFramework.ScalaTest,
    "org/scalatest/funsuite/AnyFunSuite#" -> TestFramework.ScalaTest,
    "org/scalatest/wordspec/AnyWordSpec#" -> TestFramework.ScalaTest,
    "org/scalatest/freespec/AnyFreeSpec#" -> TestFramework.ScalaTest,
    "org/scalatest/propspec/AnyPropSpec#" -> TestFramework.ScalaTest,
    "org/scalatest/featurespec/AnyFeatureSpec#" -> TestFramework.ScalaTest,
    "org/scalatest/Suite#" -> TestFramework.ScalaTest,
    "org/scalatest/TestSuite#" -> TestFramework.ScalaTest,
    "munit/FunSuite#" -> TestFramework.munit,
    "munit/Suite#" -> TestFramework.munit,
    "munit/ScalaCheckSuite#" -> TestFramework.munit,
    "junit/framework/TestCase#" -> TestFramework.JUnit,
    "org/junit/Test#" -> TestFramework.JUnit,
    "org/testng/annotations/Test#" -> TestFramework.TestNG,
    "weaver/IOSuite#" -> TestFrameworkUtils.WeaverTestFramework,
    "weaver/SimpleIOSuite#" -> TestFrameworkUtils.WeaverTestFramework,
    "weaver/MutableIOSuite#" -> TestFrameworkUtils.WeaverTestFramework,
    "zio/test/DefaultRunnableSpec#" -> TestFrameworkUtils.ZioTestFramework,
    "zio/test/RunnableSpec#" -> TestFrameworkUtils.ZioTestFramework,
    "zio/test/ZIOSpecDefault#" -> TestFrameworkUtils.ZioTestFramework,
  )

  private val knownTestSymbols: Set[String] = frameworkSymbolMap.keySet

  def fromParentSymbol(parentSymbol: String): Option[TestFramework] = {
    frameworkSymbolMap.get(parentSymbol)
  }

  def isKnownTestFrameworkSymbol(symbol: String): Boolean = {
    knownTestSymbols.contains(symbol)
  }
}

object TestFrameworkUtils {
  val WeaverTestFramework: TestFramework = TestFramework(
    List("weaver.framework.CatsEffect")
  )

  val ZioTestFramework: TestFramework = TestFramework(
    List("zio.test.sbt.ZTestFramework")
  )

  private lazy val supportedFrameworks = Set(
    TestFramework.JUnit,
    TestFramework.munit,
    TestFramework.ScalaTest,
    WeaverTestFramework,
    TestFramework.TestNG,
    ZioTestFramework,
  )

  def from(framework: Option[String]): TestFramework = framework
    .map {
      case "JUnit" => TestFramework.JUnit
      case "munit" => TestFramework.munit
      case "ScalaTest" => TestFramework.ScalaTest
      case "weaver-cats-effect" => WeaverTestFramework
      case "TestNG" => TestFramework.TestNG
      case x if x.contains("ZIO Test") => ZioTestFramework
      case _ => TestFramework(Nil)
    }
    .getOrElse(TestFramework(Nil))

  def canResolveTests(framework: TestFramework): Boolean = supportedFrameworks(
    framework
  )
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
