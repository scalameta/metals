package scala.meta.internal.metals.debug

import java.util.Collections.singletonList

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.DebugDiscoveryParams
import scala.meta.internal.metals.JavaBinary
import scala.meta.internal.metals.JsonParser._
import scala.meta.internal.metals.JvmOpts
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.MetalsBuildClient
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.clients.language.MetalsQuickPickItem
import scala.meta.internal.metals.clients.language.MetalsQuickPickParams
import scala.meta.internal.metals.config.RunType
import scala.meta.internal.metals.config.RunType._
import scala.meta.internal.metals.debug.DiscoveryFailures._
import scala.meta.internal.metals.testProvider.TestCaseEntry
import scala.meta.internal.metals.testProvider.TestSuitesProvider
import scala.meta.internal.mtags.DefinitionAlternatives.GlobalSymbol
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.mtags.Symbol
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.io.AbsolutePath

import ch.epfl.scala.{bsp4j => b}
import com.google.gson.JsonElement
import org.eclipse.lsp4j.Position

class DebugDiscovery(
    buildTargetClasses: BuildTargetClasses,
    buildTargets: BuildTargets,
    buildClient: MetalsBuildClient,
    languageClient: MetalsLanguageClient,
    semanticdbs: () => Semanticdbs,
    userConfig: () => UserConfiguration,
    workspace: AbsolutePath,
    buildTargetClassesFinder: BuildTargetClassesFinder,
    testProvider: TestSuitesProvider,
)(implicit ec: ExecutionContext) {

  private def mainClasses(bti: b.BuildTargetIdentifier) =
    buildTargetClasses.classesOf(bti).mainClasses

  private def testClasses(bti: b.BuildTargetIdentifier) =
    buildTargetClasses.classesOf(bti).testClasses

  /**
   * Tries to discover the main class to run and returns
   * DebugSessionParams that contains the shellCommand field.
   * This is used so that clients can easily run the full command
   * if they want.
   */
  def runCommandDiscovery(
      unresolvedParams: DebugDiscoveryParams
  )(implicit ec: ExecutionContext): Future[b.DebugSessionParams] = {
    debugDiscovery(unresolvedParams).flatMap(enrichWithMainShellCommand)
  }

  import DebugDiscovery._

  private def validate(
      params: DebugDiscoveryParams
  ): Try[ValidRunType.Value] = {

    val runType = RunType.fromString(params.runType)
    val pathOpt = Option(params.path).map(_.toAbsolutePath)
    val buildTarget = pathOpt.flatMap(buildTargets.inverseSources(_)).orElse {
      Option(params.buildTarget)
        .flatMap(buildTargets.findByDisplayName)
        .map(_.getId())
    }

    (runType, buildTarget, pathOpt) match {
      case (_, Some(buildTarget), _)
          if buildClient.buildHasErrors(buildTarget) =>
        Failure(WorkspaceErrorsException)
      case (_, None, Some(path)) =>
        Failure(BuildTargetNotFoundForPathException(path))
      case (None, _, _) =>
        Failure(RunType.UnknownRunTypeException(params.runType))
      case (Some(TestFile), Some(target), Some(path))
          if testClasses(target).isEmpty =>
        Failure(
          NoTestsFoundException("file", path.toString())
        )
      case (Some(TestFile | TestTarget), Some(target), _)
          if testClasses(target).isEmpty =>
        Failure(
          NoTestsFoundException("build target", displayName(target))
        )
      case (Some(TestTarget | RunClosest), None, _) =>
        Failure(NoBuildTargetSpecified)
      case (Some(tpe @ (TestFile | RunOrTestFile | RunClosest)), _, None) =>
        Failure(UndefinedPathException(tpe))
      case (Some(Run), target, _) =>
        val targetIds = target match {
          case None => buildTargets.allBuildTargetIds
          case Some(value) => List(value)
        }
        Option(params.mainClass) match {
          case Some(main) =>
            buildTargetClassesFinder
              .findMainClassAndItsBuildTarget(
                main,
                buildTarget.map(displayName),
              )
              .map(found =>
                ValidRunType.Run(found.map { case (mainClass, target) =>
                  target.getId() -> mainClass
                })
              )

          case None =>
            val classes = targetIds
              .flatMap(id => mainClasses(id).values.map(id -> _))
              .toList
            if (classes.isEmpty) {
              target match {
                case None =>
                  Failure(NothingToRun)
                case Some(targetId) =>
                  Failure(
                    BuildTargetContainsNoMainException(displayName(targetId))
                  )
              }
            } else {
              Success(ValidRunType.Run(classes))
            }
        }
      case (Some(RunOrTestFile), Some(target), Some(path)) =>
        Success(ValidRunType.RunOrTestFile(target, path))
      case (Some(TestFile), Some(target), Some(path)) =>
        Success(ValidRunType.TestFile(target, path))
      case (Some(TestTarget), Some(target), _) =>
        Success(ValidRunType.TestTarget(target))
      case (Some(RunClosest), Some(target), Some(path)) =>
        if (params.position == null) {
          Failure(UndefinedPositionException(RunClosest))
        } else {
          Success(ValidRunType.RunClosest(target, path, params.position))
        }
    }
  }

  /**
   * Given fully unresolved params this figures out the runType that was passed
   * in and then discovers either the main methods for the build target the
   * path belongs to or finds the tests for the current file or build target
   */

  def debugDiscovery(
      params: DebugDiscoveryParams
  ): Future[b.DebugSessionParams] = {
    val validated = Future.fromTry(validate(params))

    validated.flatMap {
      case ValidRunType.Run(classes) =>
        run(params, classes)
      case ValidRunType.RunOrTestFile(target, path) =>
        runOrTestFile(target, params, path)
      case ValidRunType.TestFile(target, path) =>
        testFile(path, target)
      case ValidRunType.TestTarget(target) =>
        testTarget(target)
      case ValidRunType.RunClosest(target, path, position) =>
        runClosest(target, path, position)
    }
  }

  /**
   * Finds and runs the closest runnable target (main method, test case, or test suite)
   * to the given cursor position. Uses distance-based selection with priority:
   * main methods > test cases > test suites.
   */
  def runClosest(
      buildTarget: b.BuildTargetIdentifier,
      path: AbsolutePath,
      position: Position,
  ): Future[b.DebugSessionParams] = {
    val semanticDocOpt =
      semanticdbs()
        .textDocument(path)
        .documentIncludingStale

    semanticDocOpt match {
      case Some(textDocument) =>
        findClosestRunnableTarget(textDocument, buildTarget, path, position)
      case None =>
        Future.failed(SemanticDbNotFoundException)
    }
  }

  sealed trait Distance
  case class MainDistance(distance: Int, mainClass: b.ScalaMainClass)
      extends Distance
  case class TestSuiteDistance(distance: Int, suiteFqn: String) extends Distance
  case class TestCaseDistance(distance: Int, testName: String, suiteFqn: String)
      extends Distance

  private def findClosestRunnableTarget(
      textDocument: TextDocument,
      buildTarget: b.BuildTargetIdentifier,
      path: AbsolutePath,
      position: Position,
  ): Future[b.DebugSessionParams] = {
    val classes = buildTargetClasses.classesOf(buildTarget)
    val mains = findMainClasses(textDocument, classes)
    val allTestCases = discoverTestCases(path)

    val allDistances: Seq[Distance] =
      calculateMainDistances(textDocument, mains, classes, position) ++
        calculateTestCaseDistances(allTestCases, position) ++
        calculateTestSuiteDistances(textDocument, allTestCases, position)

    findClosestTarget(allDistances) match {
      case Some(MainDistance(_, mainClass)) =>
        createMainParams(
          DebugDiscoveryParams(path.toURI.toString, "run"),
          mainClass,
          buildTarget,
        )
      case Some(TestSuiteDistance(_, suiteFqn)) =>
        createTestSuiteParams(buildTarget, suiteFqn)
      case Some(TestCaseDistance(_, testName, suiteFqn)) =>
        createTestCaseParams(buildTarget, suiteFqn, testName)
      case None =>
        Future.failed(NoRunOptionException)
    }
  }

  private def findMainClasses(
      textDocument: TextDocument,
      classes: BuildTargetClasses.Classes,
  ): Seq[b.ScalaMainClass] = {
    val mains = for {
      occurrence <- textDocument.occurrences
      if occurrence.role.isDefinition || occurrence.symbol == "scala/main#"
      symbol = occurrence.symbol
      mainClass <- {
        val normal = classes.mainClasses.get(symbol)
        val fromAnnot = DebugDiscovery
          .mainFromAnnotation(occurrence, textDocument)
          .flatMap(classes.mainClasses.get(_))
        List(normal, fromAnnot).flatten
      }
    } yield mainClass

    if (mains.nonEmpty) mains
    else DebugDiscovery.syntheticMains(textDocument, classes)
  }

  private def discoverTestCases(
      path: AbsolutePath
  ): List[(String, List[TestCaseEntry])] = {
    import scala.jdk.CollectionConverters._
    import scala.meta.internal.metals.testProvider.TestExplorerEvent.AddTestCases

    val testUpdates = testProvider.discoverTests(Some(path))
    testUpdates
      .flatMap(_.events.asScala)
      .collect { case AddTestCases(fqn, _, testCases) =>
        (fqn, testCases.asScala.toList)
      }
      .toList
  }

  private def calculateMainDistances(
      textDocument: TextDocument,
      mains: Seq[b.ScalaMainClass],
      classes: BuildTargetClasses.Classes,
      position: Position,
  ): Seq[Distance] = {
    mains.flatMap { mainClass =>
      val occOpt = textDocument.occurrences.find { occ =>
        occ.role.isDefinition && (
          classes.mainClasses.get(occ.symbol).contains(mainClass) ||
            DebugDiscovery
              .mainFromAnnotation(occ, textDocument)
              .flatMap(classes.mainClasses.get(_))
              .contains(mainClass)
        )
      }

      val syntheticOccOpt = if (occOpt.isEmpty) {
        textDocument.symbols
          .find(_.symbol.contains(mainClass.getClassName.split('.').last))
          .flatMap(symbolInfo =>
            textDocument.occurrences.find(_.symbol == symbolInfo.symbol)
          )
      } else None

      (occOpt.orElse(syntheticOccOpt)).flatMap(_.range).map { range =>
        val lineDiff = math.abs(range.startLine - position.getLine)
        MainDistance(lineDiff, mainClass)
      }
    }
  }

  private def calculateTestCaseDistances(
      allTestCases: List[(String, List[TestCaseEntry])],
      position: Position,
  ): Seq[Distance] = {
    allTestCases.flatMap { case (suiteFqn, cases) =>
      cases.map { entry =>
        val range = entry.location.getRange()
        val start = range.getStart()
        val lineDiff = math.abs(start.getLine() - position.getLine)
        TestCaseDistance(lineDiff, entry.name, suiteFqn)
      }
    }
  }

  private def calculateTestSuiteDistances(
      textDocument: TextDocument,
      allTestCases: List[(String, List[TestCaseEntry])],
      position: Position,
  ): Seq[Distance] = {
    val testClassOccurrences = textDocument.occurrences.filter { occ =>
      occ.role.isDefinition && (
        allTestCases.exists { case (suiteFqn, _) =>
          occ.symbol.contains(suiteFqn.split('.').last)
        }
      )
    }

    testClassOccurrences.flatMap { occ =>
      occ.range.flatMap { range =>
        val lineDiff = math.abs(range.startLine - position.getLine)

        allTestCases.headOption
          .map(_._1)
          .map { suiteFqn =>
            TestSuiteDistance(lineDiff, suiteFqn)
          }
      }
    }
  }

  private def findClosestTarget(
      allDistances: Seq[Distance]
  ): Option[Distance] = {
    allDistances match {
      case Nil => None
      case _ =>
        allDistances.sortBy {
          case MainDistance(distance, _) => (distance, 0)
          case TestCaseDistance(distance, _, _) => (distance, 1)
          case TestSuiteDistance(distance, _) => (distance, 2)
        }.headOption
    }
  }

  private def createTestSuiteParams(
      buildTarget: b.BuildTargetIdentifier,
      suiteFqn: String,
  ): Future[b.DebugSessionParams] = {
    import scala.jdk.CollectionConverters._
    val params = new b.DebugSessionParams(singletonList(buildTarget))
    params.setDataKind(b.TestParamsDataKind.SCALA_TEST_SUITES_SELECTION)
    val suiteSelection =
      new b.ScalaTestSuiteSelection(suiteFqn, List.empty[String].asJava)
    val suites = new b.ScalaTestSuites(
      List(suiteSelection).asJava,
      Nil.asJava,
      Nil.asJava,
    )
    params.setData(suites.toJson)
    Future.successful(params)
  }

  private def createTestCaseParams(
      buildTarget: b.BuildTargetIdentifier,
      suiteFqn: String,
      testName: String,
  ): Future[b.DebugSessionParams] = {
    import scala.jdk.CollectionConverters._
    val params = new b.DebugSessionParams(singletonList(buildTarget))
    params.setDataKind(b.TestParamsDataKind.SCALA_TEST_SUITES_SELECTION)
    val suiteSelection =
      new b.ScalaTestSuiteSelection(suiteFqn, List(testName).asJava)
    val suites = new b.ScalaTestSuites(
      List(suiteSelection).asJava,
      Nil.asJava,
      Nil.asJava,
    )
    params.setData(suites.toJson)
    Future.successful(params)
  }

  private def run(
      params: DebugDiscoveryParams,
      classes: List[(b.BuildTargetIdentifier, b.ScalaMainClass)],
  ): Future[b.DebugSessionParams] = {
    val targetToMainClasses =
      classes.groupBy(_._1).view.mapValues(_.map(_._2)).toMap
    findMainToRun(
      targetToMainClasses,
      params,
    )
  }

  private def testFile(
      path: AbsolutePath,
      target: b.BuildTargetIdentifier,
  ): Future[b.DebugSessionParams] =
    semanticdbs()
      .textDocument(path)
      .documentIncludingStale
      .fold[Future[Seq[BuildTargetClasses.FullyQualifiedClassName]]] {
        Future.failed(SemanticDbNotFoundException)
      } { textDocument =>
        Future {
          for {
            symbolInfo <- textDocument.symbols
            symbol = symbolInfo.symbol
            testSymbolInfo <- testClasses(target).get(symbol)
          } yield testSymbolInfo.fullyQualifiedName
        }
      }
      .map { tests =>
        val params = new b.DebugSessionParams(
          singletonList(target)
        )
        params.setDataKind(
          b.TestParamsDataKind.SCALA_TEST_SUITES
        )
        params.setData(tests.asJava.toJson)
        params
      }

  private def testTarget(
      target: b.BuildTargetIdentifier
  ): Future[b.DebugSessionParams] = {
    Future {
      val params = new b.DebugSessionParams(
        singletonList(target)
      )
      params.setDataKind(b.TestParamsDataKind.SCALA_TEST_SUITES)
      params.setData(
        testClasses(target).values
          .map(_.fullyQualifiedName)
          .toList
          .asJava
          .toJson
      )
      params
    }
  }
  private def runOrTestFile(
      buildTarget: b.BuildTargetIdentifier,
      params: DebugDiscoveryParams,
      path: AbsolutePath,
  ): Future[b.DebugSessionParams] = {
    semanticdbs()
      .textDocument(path)
      .documentIncludingStale
      .fold[Future[b.DebugSessionParams]] {
        Future.failed(SemanticDbNotFoundException)
      } { textDocument =>
        val classes = buildTargetClasses.classesOf(buildTarget)
        lazy val tests = for {
          symbolInfo <- textDocument.symbols
          symbol = symbolInfo.symbol
          testSymbolInfo <- classes.testClasses.get(symbol)
        } yield testSymbolInfo.fullyQualifiedName
        val mainWithFallback = findMainClasses(textDocument, classes)
        if (mainWithFallback.nonEmpty) {
          findMainToRun(Map(buildTarget -> mainWithFallback.toList), params)
        } else if (tests.nonEmpty) {
          DebugProvider.envFromFile(workspace, Option(params.envFile)).map {
            envFromFile =>
              val env =
                Option(params.env).toList.flatMap(DebugProvider.createEnvList)

              val jvmOpts =
                JvmOpts.fromWorkspaceOrEnvForTest(workspace).getOrElse(Nil)
              val scalaTestSuite = new b.ScalaTestSuites(
                tests
                  .map(
                    new b.ScalaTestSuiteSelection(
                      _,
                      Nil.asJava,
                    )
                  )
                  .asJava,
                Option(params.jvmOptions)
                  .map(jvmOpts ++ _.asScala)
                  .getOrElse(jvmOpts)
                  .asJava,
                (envFromFile ::: env).asJava,
              )
              val debugParams = new b.DebugSessionParams(
                singletonList(buildTarget)
              )
              debugParams.setDataKind(
                b.TestParamsDataKind.SCALA_TEST_SUITES_SELECTION
              )
              debugParams.setData(scalaTestSuite.toJson)
              debugParams
          }
        } else {
          Future.failed(NoRunOptionException)
        }
      }
  }

  private def findMainToRun(
      classes: Map[b.BuildTargetIdentifier, List[b.ScalaMainClass]],
      params: DebugDiscoveryParams,
  )(implicit ec: ExecutionContext): Future[b.DebugSessionParams] = {

    classes.toList match {
      case (buildTarget, main :: Nil) :: Nil =>
        createMainParams(
          params,
          main,
          buildTarget,
        )
      case multiple =>
        val targetMainClasses = multiple.flatMap { case (target, mains) =>
          mains.map(m => (target, m))
        }
        requestMain(targetMainClasses).flatMap { case (target, main) =>
          createMainParams(
            params,
            main,
            target,
          )
        }

    }
  }

  private def enrichWithMainShellCommand(
      params: b.DebugSessionParams
  )(implicit ec: ExecutionContext): Future[b.DebugSessionParams] = {
    val future = params.getData() match {
      case json: JsonElement
          if params.getDataKind == b.DebugSessionParamsDataKind.SCALA_MAIN_CLASS =>
        json.as[b.ScalaMainClass] match {
          case Success(main) if params.getTargets().size > 0 =>
            val javaBinary = buildTargets
              .scalaTarget(params.getTargets().get(0))
              .flatMap(scalaTarget =>
                JavaBinary.javaBinaryFromPath(scalaTarget.jvmHome)
              )
              .orElse(userConfig().usedJavaBinary)
            buildTargetClasses
              .jvmRunEnvironment(params.getTargets().get(0), isTests = false)
              .map { envItem =>
                val updatedData = envItem.zip(javaBinary) match {
                  case None =>
                    main.toJson
                  case Some((env, javaHome)) =>
                    ExtendedScalaMainClass(
                      main,
                      env,
                      javaHome,
                      workspace,
                    ).toJson
                }
                params.setData(updatedData)
              }
          case _ => Future.unit
        }

      case _ => Future.unit
    }

    future.map { _ =>
      params
    }
  }

  private def createMainParams(
      params: DebugDiscoveryParams,
      main: b.ScalaMainClass,
      buildTargetIdentifier: b.BuildTargetIdentifier,
  )(implicit ec: ExecutionContext): Future[b.DebugSessionParams] = {
    val env = Option(params.env).toList.flatMap(DebugProvider.createEnvList)
    DebugProvider.createMainParams(
      workspace,
      main,
      buildTargetIdentifier,
      Option(params.args),
      Option(params.jvmOptions),
      env,
      Option(params.envFile),
    )
  }

  /**
   * Given a BuildTargetIdentifier either get the displayName of that build
   * target or default to the full URI to display to the user.
   */
  private def displayName(buildTargetIdentifier: b.BuildTargetIdentifier) =
    buildTargets
      .info(buildTargetIdentifier)
      .map(_.getDisplayName)
      .getOrElse(buildTargetIdentifier.getUri)

  private def requestMain(
      mainClasses: List[(b.BuildTargetIdentifier, b.ScalaMainClass)]
  )(implicit
      ec: ExecutionContext
  ): Future[(b.BuildTargetIdentifier, b.ScalaMainClass)] = {
    def invalidThrow = throw new RuntimeException("No valid class was chosen")
    languageClient
      .metalsQuickPick(
        new MetalsQuickPickParams(
          mainClasses.map { case (_, m) =>
            val cls = m.getClassName()
            new MetalsQuickPickItem(cls, cls)
          }.asJava,
          placeHolder = Messages.MainClass.message,
        )
      )
      .asScala
      .map {
        case Some(choice) =>
          mainClasses
            .find { case (_, clazz) =>
              clazz.getClassName() == choice.itemId
            }
            .getOrElse(invalidThrow)
        case None => invalidThrow
      }
  }

}

object DebugDiscovery {

  object ValidRunType {

    sealed trait Value

    case class Run(classes: List[(b.BuildTargetIdentifier, b.ScalaMainClass)])
        extends Value
    case class RunOrTestFile(
        target: b.BuildTargetIdentifier,
        path: AbsolutePath,
    ) extends Value
    case class TestFile(target: b.BuildTargetIdentifier, path: AbsolutePath)
        extends Value
    case class TestTarget(target: b.BuildTargetIdentifier) extends Value
    case class RunClosest(
        target: b.BuildTargetIdentifier,
        path: AbsolutePath,
        position: Position,
    ) extends Value
  }

  /**
   * Given an occurence and a text document return the symbol of a main method
   * that could be defined using the Scala 3 @main annotation.
   *
   * @param occurrence The symbol occurence you're checking against the document.
   * @param textDocument The document of the current file.
   * @return Possible symbol name of main.
   */
  def mainFromAnnotation(
      occurrence: SymbolOccurrence,
      textDocument: TextDocument,
  ): Option[String] = {
    if (occurrence.symbol == "scala/main#") {
      occurrence.range match {
        case Some(range) =>
          val closestOccurence = textDocument.occurrences.minBy { occ =>
            occ.range
              .filter { rng =>
                occ.symbol != "scala/main#" &&
                rng.endLine - range.endLine >= 0 &&
                rng.endCharacter - rng.startCharacter > 0
              }
              .map(rng =>
                (
                  rng.endLine - range.endLine,
                  rng.endCharacter - range.endCharacter,
                )
              )
              .getOrElse((Int.MaxValue, Int.MaxValue))
          }
          dropSourceFromToplevelSymbol(closestOccurence.symbol)

        case None => None
      }
    } else {
      None
    }

  }

  def syntheticMains(
      textDocument: TextDocument,
      classes: BuildTargetClasses.Classes,
  ): Seq[b.ScalaMainClass] = {
    val symbolsWithMain = textDocument.symbols.filter(info =>
      classes.mainClasses.contains(info.symbol)
    )
    for {
      sym <- symbolsWithMain
      if ! {
        textDocument.occurrences.exists(occ =>
          occ.symbol == sym.symbol && occ.role.isDefinition
        )
      }
      main <- classes.mainClasses
        .get(sym.symbol)
    } yield main
  }

  import scala.meta.internal.semanticdb.Scala._

  /**
   * Converts Scala3 sorceToplevelSymbol into a plain one that corresponds to class name.
   * From `3.1.0` plain names were removed from occurrences because they are synthetic.
   * Example:
   *   `foo/Foo$package.mainMethod().` -> `foo/mainMethod#`
   */
  private def dropSourceFromToplevelSymbol(symbol: String): Option[String] = {
    Symbol(symbol) match {
      case GlobalSymbol(
            GlobalSymbol(
              owner,
              Descriptor.Term(_),
            ),
            Descriptor.Method(name, _),
          ) =>
        val converted = GlobalSymbol(owner, Descriptor.Term(name))
        Some(converted.value)
      case _ =>
        None
    }
  }
}
