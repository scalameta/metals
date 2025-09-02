package scala.meta.internal.metals.debug

import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.meta.internal.metals.{BatchedFunction, BuildTargets, SemanticdbFeatureProvider}
import scala.meta.internal.metals.MetalsEnrichments.*
import scala.meta.internal.metals.debug.BuildTargetClasses.Classes
import scala.meta.internal.metals.debug.BuildTargetClasses.TestSymbolInfo
import scala.meta.internal.semanticdb.Scala.Descriptor
import scala.meta.internal.semanticdb.Scala.Symbols
import bloop.config.Config.TestFramework
import ch.epfl.scala.bsp4j as b

import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.parsing.Trees
import scala.meta.internal.semanticdb.{ClassSignature, SymbolInformation, TextDocument, TextDocuments, TypeRef}
import scala.meta.io.AbsolutePath

/**
 * In-memory index of main class symbols grouped by their enclosing build target
 * 
 * POC: Test Framework Detection using SemanticDB
 * ===============================================
 * 
 * This class now implements SemanticdbFeatureProvider to leverage semanticdb data
 * for accurate test framework detection. The onChange method processes semanticdb
 * documents to analyze class inheritance and detect test frameworks.
 * 
 * How it works:
 * 1. When semanticdb files are updated, onChange() is called with TextDocuments
 * 2. For each class symbol in the document, we extract its ClassSignature
 * 3. We analyze the parent classes from ClassSignature.parents
 * 4. We match parent symbols against known test framework base classes
 * 5. The detected framework is stored in TestSymbolInfo for later use
 * 
 * Example semanticdb analysis:
 * - Class: com.example.MyTest extends org.scalatest.funsuite.AnyFunSuite
 * - Parent symbol: "org/scalatest/funsuite/AnyFunSuite#"
 * - Detected framework: TestFramework.ScalaTest
 * 
 * This replaces the hardcoded framework assumption in Bazel test discovery.
 */
final class BuildTargetClasses(
    val buildTargets: BuildTargets,
    val trees: Trees,
    val semanticdbs: () => Semanticdbs
)(implicit
    val ec: ExecutionContext
) extends SemanticdbFeatureProvider {
private val index = TrieMap.empty[b.BuildTargetIdentifier, Classes]

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
    // POC: Process semanticdb data to detect test frameworks for classes in the path
    // This method is called whenever semanticdb files are updated, providing us with
    // rich type information including class inheritance data.
    buildTargets.inverseSources(path) match {
      case Some(targetId) =>
        docs.documents.foreach { doc =>
          processSemanticdbForTestFrameworkDetection(doc, targetId)
        }
      case None => // No build target found for this path
    }
  }

  override def onDelete(path: AbsolutePath): Unit = ()
  override def reset(): Unit = ()

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
            if (isBazelBuild(targets0)) {
              // For Bazel, discover test classes from source files instead of BSP
//              Future.successful(cacheBazelTestClasses(classes, targets0))
            Future.unit
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

  /**
   * Process semanticdb data to detect test frameworks for classes
   */
  private def processSemanticdbForTestFrameworkDetection(
      doc: TextDocument,
      targetId: b.BuildTargetIdentifier
  ): Unit = {
    // Only process if this is a Bazel build target (where we do custom test discovery)
    if (isBazelTestTarget(targetId)) {
      val classes = index.getOrElseUpdate(targetId, new Classes)
      
      // Find class symbols in the document
      doc.symbols.foreach { symbolInfo =>
        symbolInfo.signature match {
          case classSig: ClassSignature =>
            val className = symbolToClassName(symbolInfo.symbol)
            if (className.nonEmpty && isLikelyTestClass(symbolInfo, classSig)) {
              val framework = detectTestFramework(classSig, doc)
              val symbol = symbolInfo.symbol
              val testInfo = TestSymbolInfo(className, framework)
              classes.testClasses.put(symbol, testInfo)
              
              scribe.debug(s"Detected test class: $className with framework: ${framework.names.headOption.getOrElse("Unknown")}")
            }
          case _ => // Ignore non-class symbols
        }
      }
    }
  }

  /**
   * Detect test framework based on class inheritance using semanticdb data
   */
  private def detectTestFramework(
      classSig: ClassSignature,
      doc: TextDocument
  ): TestFramework = {
    val parentSymbols = extractParentSymbols(classSig)
    
    // Check direct parents first
    parentSymbols.foreach { parentSymbol =>
      TestFrameworkDetector.fromParentSymbol(parentSymbol) match {
        case Some(framework) => return framework
        case None => // Continue searching
      }
    }
    
    // If no direct match, try recursive parent search
    parentSymbols.foreach { parentSymbol =>
      findFrameworkRecursively(parentSymbol, doc, visited = Set.empty) match {
        case Some(framework) => return framework
        case None => // Continue searching
      }
    }
    
    // Default fallback
    TestFramework(Nil)
  }

  /**
   * Extract parent class symbols from ClassSignature
   */
  private def extractParentSymbols(classSig: ClassSignature): List[String] = {
    classSig.parents.collect {
      case TypeRef(_, parentSymbol, _) => parentSymbol
    }.toList
  }

  /**
   * Recursively search for test framework in parent hierarchy
   */
  private def findFrameworkRecursively(
      parentSymbol: String,
      doc: TextDocument,
      visited: Set[String]
  ): Option[TestFramework] = {
    if (visited.contains(parentSymbol)) return None
    
    // Check if this parent symbol matches a known framework
    TestFrameworkDetector.fromParentSymbol(parentSymbol) match {
      case Some(framework) => return Some(framework)
      case None => // Continue searching
    }
    
    // Find parent class in current document
    doc.symbols.find(_.symbol == parentSymbol) match {
      case Some(parentInfo) =>
        parentInfo.signature match {
          case parentClassSig: ClassSignature =>
            val grandParents = extractParentSymbols(parentClassSig)
            val newVisited = visited + parentSymbol
            
            grandParents.foreach { grandParent =>
              findFrameworkRecursively(grandParent, doc, newVisited) match {
                case Some(framework) => return Some(framework)
                case None => // Continue
              }
            }
          case _ => // Not a class
        }
      case None => // Parent not found in current document
    }
    
    None
  }

  /**
   * Check if a class is likely a test class based on naming conventions and structure
   */
  private def isLikelyTestClass(
      classInfo: SymbolInformation,
      classSig: ClassSignature
  ): Boolean = {
    val className = symbolToClassName(classInfo.symbol)
    val hasTestInName = className.toLowerCase.contains("test") || 
                       className.toLowerCase.contains("spec") ||
                       className.toLowerCase.contains("suite")
    
    val hasTestFrameworkParent = extractParentSymbols(classSig).exists { parent =>
      TestFrameworkDetector.isKnownTestFrameworkSymbol(parent)
    }
    
    hasTestInName || hasTestFrameworkParent
  }

  /**
   * Convert symbol to class name
   */
  private def symbolToClassName(symbol: String): String = {
    symbol
      .stripPrefix("_empty_/")
      .stripSuffix("#")
      .replace("/", ".")
  }

  private def isBazelBuild(targets: Seq[b.BuildTargetIdentifier]): Boolean = {
    targets.exists { target =>
      val uri = target.getUri
      uri.contains("bazel") || isBazelTestTarget(target)
    }
  }

  private def isBazelTestTarget(target: b.BuildTargetIdentifier): Boolean = {
    val uri = target.getUri
    uri.contains("test")
  }

  private def cacheBazelTestClasses(
      classes: Map[b.BuildTargetIdentifier, Classes],
      targets: Seq[b.BuildTargetIdentifier],
  ): Unit = {
    targets.foreach { target =>
      val testClasses = fetchTestClassNamesFromBuildTarget(target)
      testClasses.foreach { case (symbol, testInfo) =>
        classes(target).testClasses.put(symbol, testInfo)
      }
    }
  }

  private def fetchTestClassNamesFromBuildTarget(
      target: b.BuildTargetIdentifier
  ): Map[String, TestSymbolInfo] = {
    import scala.jdk.CollectionConverters._

    // Get all source files that belong to this specific build target
    val sourceFiles = buildTargets.sourceItemsToBuildTargets
      .filter { case (_, buildTargetIds) =>
        buildTargetIds.asScala.toList.contains(target)
      }
      .map(_._1) // Get the AbsolutePath
      .filter(_.isScalaFilename) // Only Scala files
      .toList

    // Extract class names from each source file
    val classNames = sourceFiles.flatMap { sourcePath =>
      trees.get(sourcePath) match {
        case Some(tree) => extractClassNamesFromTree(tree)
        case None => Nil
      }
    }

    // Convert to the expected Map[Symbol, TestSymbolInfo] format
    // Note: This method is now complemented by the onChange method which provides
    // more accurate framework detection using semanticdb data. This fallback
    // maintains compatibility for cases where semanticdb data isn't available.
    classNames.map { className =>
      val symbol: String = (className.replace('.', '/') + "#")

      // Fallback to ScalaTest if no semanticdb-based detection is available
      val framework = TestFrameworkUtils.from(Some("ScalaTest"))
      symbol -> TestSymbolInfo(className, framework)
    }.toMap
  }

  private def extractClassNamesFromTree(tree: scala.meta.Tree): List[String] = {
    import scala.collection.mutable
    import scala.meta.{Defn, Pkg}

    val classNames = mutable.ListBuffer[String]()

    def traverse(t: scala.meta.Tree, packagePrefix: String = ""): Unit = {
      t match {
        case pkg: Pkg =>
          val pkgName = pkg.ref.toString()
          val newPrefix =
            if (packagePrefix.isEmpty) pkgName
            else s"$packagePrefix.$pkgName"
          pkg.stats.foreach(traverse(_, newPrefix))

        case cls: Defn.Class =>
          val fullName =
            if (packagePrefix.isEmpty) cls.name.value
            else s"$packagePrefix.${cls.name.value}"
          classNames += fullName
          // Also traverse nested classes
          cls.templ.stats.foreach(traverse(_, packagePrefix))

        case obj: Defn.Object =>
          val fullName =
            if (packagePrefix.isEmpty) obj.name.value
            else s"$packagePrefix.${obj.name.value}"
          classNames += fullName
          obj.templ.stats.foreach(traverse(_, packagePrefix))

        case trt: Defn.Trait =>
          val fullName =
            if (packagePrefix.isEmpty) trt.name.value
            else s"$packagePrefix.${trt.name.value}"
          classNames += fullName
          trt.templ.stats.foreach(traverse(_, packagePrefix))

        case other =>
          // Traverse children for nested definitions
          other.children.foreach(traverse(_, packagePrefix))
      }
    }

    traverse(tree)
    classNames.toList
  }
}

/**
 * POC: Detects test frameworks based on parent class symbols from semanticdb
 * 
 * This object maps semanticdb class symbols to their corresponding test frameworks.
 * The symbols follow semanticdb naming conventions where:
 * - Package separators are '/' instead of '.'
 * - Class symbols end with '#'
 * 
 * Example mappings:
 * - "org/scalatest/funsuite/AnyFunSuite#" -> TestFramework.ScalaTest
 * - "munit/FunSuite#" -> TestFramework.munit
 * - "junit/framework/TestCase#" -> TestFramework.JUnit
 * 
 * The detection works by analyzing ClassSignature.parents from semanticdb data
 * and matching them against these known framework base classes.
 */
object TestFrameworkDetector {
  
  // Mapping from parent class symbols to test frameworks
  private val frameworkSymbolMap: Map[String, TestFramework] = Map(
    // ScalaTest frameworks
    "org/scalatest/flatspec/AnyFlatSpec#" -> TestFramework.ScalaTest,
    "org/scalatest/funspec/AnyFunSpec#" -> TestFramework.ScalaTest,
    "org/scalatest/funsuite/AnyFunSuite#" -> TestFramework.ScalaTest,
    "org/scalatest/wordspec/AnyWordSpec#" -> TestFramework.ScalaTest,
    "org/scalatest/freespec/AnyFreeSpec#" -> TestFramework.ScalaTest,
    "org/scalatest/propspec/AnyPropSpec#" -> TestFramework.ScalaTest,
    "org/scalatest/featurespec/AnyFeatureSpec#" -> TestFramework.ScalaTest,
    "org/scalatest/Suite#" -> TestFramework.ScalaTest,
    "org/scalatest/TestSuite#" -> TestFramework.ScalaTest,
    
    // munit frameworks  
    "munit/FunSuite#" -> TestFramework.munit,
    "munit/Suite#" -> TestFramework.munit,
    "munit/ScalaCheckSuite#" -> TestFramework.munit,
    
    // JUnit frameworks
    "junit/framework/TestCase#" -> TestFramework.JUnit,
    "org/junit/Test#" -> TestFramework.JUnit,
    
    // TestNG frameworks
    "org/testng/annotations/Test#" -> TestFramework.TestNG,
    
    // Weaver frameworks
    "weaver/IOSuite#" -> TestFrameworkUtils.WeaverTestFramework,
    "weaver/SimpleIOSuite#" -> TestFrameworkUtils.WeaverTestFramework,
    "weaver/MutableIOSuite#" -> TestFrameworkUtils.WeaverTestFramework,
    
    // ZIO Test frameworks
    "zio/test/DefaultRunnableSpec#" -> TestFrameworkUtils.ZioTestFramework,
    "zio/test/RunnableSpec#" -> TestFrameworkUtils.ZioTestFramework,
    "zio/test/ZIOSpecDefault#" -> TestFrameworkUtils.ZioTestFramework,
  )
  
  // Common parent symbols that might indicate test classes
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
