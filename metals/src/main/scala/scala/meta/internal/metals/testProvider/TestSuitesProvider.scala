package scala.meta.internal.metals.testProvider

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.implementation.TextDocumentWithPath
import scala.meta.internal.metals.BaseCommand
import scala.meta.internal.metals.BatchedFunction
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.ClientCommands
import scala.meta.internal.metals.ClientConfiguration
import scala.meta.internal.metals.JsonParser._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ScalaTestSuiteSelection
import scala.meta.internal.metals.ScalaTestSuites
import scala.meta.internal.metals.SemanticdbFeatureProvider
import scala.meta.internal.metals.TestUserInterfaceKind
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.codelenses.CodeLens
import scala.meta.internal.metals.debug.BuildTargetClasses
import scala.meta.internal.metals.debug.TestFrameworkUtils
import scala.meta.internal.metals.testProvider.TestExplorerEvent._
import scala.meta.internal.metals.testProvider.frameworks.JunitTestFinder
import scala.meta.internal.metals.testProvider.frameworks.MunitTestFinder
import scala.meta.internal.metals.testProvider.frameworks.ScalatestTestFinder
import scala.meta.internal.metals.testProvider.frameworks.TestNGTestFinder
import scala.meta.internal.metals.testProvider.frameworks.WeaverCatsEffectTestFinder
import scala.meta.internal.metals.testProvider.frameworks.ZioTestFinder
import scala.meta.internal.mtags
import scala.meta.internal.mtags.GlobalSymbolIndex
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.parsing.Trees
import scala.meta.internal.semanticdb
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.semanticdb.TextDocuments
import scala.meta.io.AbsolutePath

import bloop.config.Config
import ch.epfl.scala.bsp4j.BuildTarget
import ch.epfl.scala.bsp4j.ScalaPlatform
import ch.epfl.scala.{bsp4j => b}
import org.eclipse.{lsp4j => l}

final class TestSuitesProvider(
    buildTargets: BuildTargets,
    buildTargetClasses: BuildTargetClasses,
    trees: Trees,
    symbolIndex: GlobalSymbolIndex,
    semanticdbs: () => Semanticdbs,
    buffers: Buffers,
    clientConfig: ClientConfiguration,
    userConfig: () => UserConfiguration,
    client: MetalsLanguageClient,
    folderName: String,
    folderUri: AbsolutePath,
)(implicit ec: ExecutionContext)
    extends SemanticdbFeatureProvider
    with CodeLens {

  private val index = new TestSuitesIndex
  private val junitTestFinder = new JunitTestFinder
  private val testNGTestFinder = new TestNGTestFinder
  private val munitTestFinder =
    new MunitTestFinder(trees, symbolIndex, semanticdbs)
  private val scalatestTestFinder =
    new ScalatestTestFinder(trees, symbolIndex, semanticdbs)
  private val weaverCatsEffect =
    new WeaverCatsEffectTestFinder(trees, symbolIndex, semanticdbs)
  private val zioTestFinder = new ZioTestFinder(trees)

  private def isExplorerEnabled = clientConfig.isTestExplorerProvider() &&
    userConfig().testUserInterface == TestUserInterfaceKind.TestExplorer

  private def isCodeLensEnabled = clientConfig.isDebuggingProvider() &&
    userConfig().testUserInterface == TestUserInterfaceKind.CodeLenses

  private def isSuiteRefreshEnabled = isExplorerEnabled || isCodeLensEnabled

  // Applies to code lenses only
  override def isEnabled: Boolean = isCodeLensEnabled

  val refreshTestSuites: BatchedFunction[Unit, Unit] =
    BatchedFunction.fromFuture(
      { _ =>
        if (isSuiteRefreshEnabled) doRefreshTestSuites()
        else Future.unit
      },
      "refreshTestSuites",
    )

  private val updateTestCases
      : BatchedFunction[(AbsolutePath, TextDocument), Unit] =
    BatchedFunction.fromFuture(
      { args =>
        Future
          .traverse(args) { case (path, doc) => refreshTestCases(path, doc) }
          .map(_ => ())
      },
      "updateTestCases",
    )

  override def onChange(docs: TextDocuments, file: AbsolutePath): Unit =
    docs.documents.headOption.foreach {
      case doc if isExplorerEnabled => updateTestCases((file, doc))
      case _ => ()
    }

  override def onDelete(file: AbsolutePath): Unit = ()
  override def reset(): Unit = ()

  def onFileDelete(file: AbsolutePath): Unit = {
    val removed = index.remove(file)
    if (isExplorerEnabled) {
      val removeEvents = removed
        .groupBy(_.buildTarget)
        .map { case (buildTarget, entries) =>
          buildTargetUpdate(
            buildTarget,
            entries.map(_.suiteDetails.asRemoveEvent),
          )
        }
        .toList

      updateClientIfNonEmpty(removeEvents)
    }
  }

  override def codeLenses(
      textDocumentWithPath: TextDocumentWithPath
  ): Future[Seq[l.CodeLens]] = Future {
    val path = textDocumentWithPath.filePath
    for {
      target <- buildTargets.inverseSources(path).toList
      cases <- getTestCasesForPath(
        path,
        Some(textDocumentWithPath.textDocument),
      )
      lens <- cases.events.asScala.collect { case AddTestCases(fqn, _, cases) =>
        cases.asScala.flatMap { entry =>
          val c = ScalaTestSuiteSelection(fqn, List(entry.name).asJava)
          val params = new b.DebugSessionParams(
            List(target).asJava
          )
          params.setDataKind(
            b.TestParamsDataKind.SCALA_TEST_SUITES_SELECTION
          )
          params.setData(
            ScalaTestSuites(List(c).asJava, Nil.asJava, Nil.asJava).toJson
          )
          def lens(name: String, cmd: BaseCommand) = new l.CodeLens(
            entry.location.getRange(),
            new l.Command(name, cmd.id, List[Object](params).asJava),
            null,
          )
          List(
            lens("test case", ClientCommands.StartRunSession),
            lens("debug test case", ClientCommands.StartDebugSession),
          )
        }
      }.flatten
    } yield lens
  }

  /**
   * Check if opened file contains test suite and update test cases if yes.
   */
  def didOpen(file: AbsolutePath): Future[Unit] =
    if (isExplorerEnabled && index.contains(file)) Future {
      val buildTargetUpdates = getTestCasesForPath(file, None)
      updateClientIfNonEmpty(buildTargetUpdates)
    }
    else Future.unit

  /**
   * Discover tests:
   * - for a workspace if path isn't defined
   * - for a given file if path is defined
   */
  def discoverTests(
      path: Option[AbsolutePath]
  ): List[BuildTargetUpdate] = {
    path match {
      case Some(path0) => getTestCasesForPath(path0, None)
      case None =>
        index.allSuites.map { case (buildTarget, entries) =>
          buildTargetUpdate(
            buildTarget,
            entries.map(_.suiteDetails.asAddEvent).toList,
          )
        }.toList
    }
  }

  /**
   * For test suites located in a given path,
   * update their location (range) and their children (test cases).
   *
   * For children, only consider 'relevant' files which have already discovered children.
   * Metals have to update them because they can be visible to the user via:
   * 1. Test Explorer view can be opened and tree view is visible
   * 2. test suite's file can be opened and test cases are visible
   */
  private def refreshTestCases(
      file: AbsolutePath,
      doc: TextDocument,
  ): Future[Unit] = Future {
    val suiteLocationChanged =
      if (index.contains(file))
        getTestSuitesLocationUpdates(file, doc)
      else Nil

    val buildTargetUpdates =
      if (index.shouldBeUpdated(file, doc.md5))
        getTestCasesForPath(file, Some(doc))
      else Nil

    val updates = suiteLocationChanged ::: buildTargetUpdates
    updateClientIfNonEmpty(updates)
  }

  private def updateClientIfNonEmpty(updates: Seq[BuildTargetUpdate]): Unit =
    if (updates.nonEmpty) {
      val params =
        ClientCommands.UpdateTestExplorer.toExecuteCommandParams(updates: _*)
      client.metalsExecuteClientCommand(params)
    }

  private def getTestSuitesLocationUpdates(
      path: AbsolutePath,
      doc: TextDocument,
  ): List[BuildTargetUpdate] = {
    val events = for {
      metadata <- index.getMetadata(path).toList
      entry <- metadata.entries
      symbol <- doc.symbols.find(si =>
        si.symbol == entry.suiteDetails.symbol.value
      )
      loc <- doc.toLocation(path.toURI, symbol.symbol)
      if loc != entry.suiteDetails.location
    } yield {
      val event = UpdateSuiteLocation(
        fullyQualifiedClassName = entry.suiteDetails.fullyQualifiedName.value,
        className = entry.suiteDetails.className.value,
        location = loc,
      )
      (entry.buildTarget, event)
    }

    events
      .groupBy { case (target, _) => target }
      .map { case (buildTarget, events) =>
        buildTargetUpdate(
          buildTarget,
          events.map { case (_, event) => event },
        )
      }
      .toList
  }

  /**
   * Retrieve test cases for a given file.
   * Test suites, just as file which contains them, can belong to
   * multiple build targets and we need to discover test suites
   * for all of them.
   *
   * Even an empty list is being sent, it can mean that all testcases were deleted.
   */
  private def getTestCasesForPath(
      path: AbsolutePath,
      textDocument: Option[TextDocument],
  ): List[BuildTargetUpdate] = {
    val buildTargetUpdates =
      for {
        metadata <- index.getMetadata(path).toList
        events = {
          val suites = metadata.entries.map(_.suiteDetails).distinct
          val canResolve = suites.exists(suite =>
            TestFrameworkUtils.canResolveTests(suite.framework)
          )
          if (canResolve) getTestCasesForSuites(path, suites, textDocument)
          else Seq.empty
        }
        buildTarget <- metadata.entries.map(_.buildTarget).distinct
      } yield {
        buildTargetUpdate(buildTarget, events)
      }
    buildTargetUpdates
  }

  /**
   * Searches for test cases for a given path for a provided test suites.
   *
   * If semanticDB isn't defined then it'll fetched when necessary.
   * - file was compiled and it's opened - semanticdb will be defined
   * - suite is discovered for the first time and the file is opened - semanticDB is not defined
   * - file which contains suites were opened, discover tests and show them to user - semanticDB is not defined
   */
  private def getTestCasesForSuites(
      path: AbsolutePath,
      suites: Seq[TestSuiteDetails],
      doc: Option[TextDocument],
  ): Seq[AddTestCases] = {
    doc
      .orElse(semanticdbs().textDocument(path).documentIncludingStale)
      .map { semanticdb =>
        suites.flatMap { suite =>
          val testCases = suite.framework match {
            case Config.TestFramework.JUnit =>
              junitTestFinder.findTests(
                doc = semanticdb,
                path = path,
                suiteSymbol = suite.symbol,
              )
            case Config.TestFramework.munit =>
              munitTestFinder.findTests(
                doc = semanticdb,
                path = path,
                suiteName = suite.fullyQualifiedName,
                symbol = suite.symbol,
              )
            case Config.TestFramework.ScalaTest =>
              scalatestTestFinder.findTests(
                doc = semanticdb,
                path = path,
                suiteName = suite.fullyQualifiedName,
                symbol = suite.symbol,
              )
            case TestFrameworkUtils.WeaverTestFramework =>
              weaverCatsEffect.findTests(
                doc = semanticdb,
                path = path,
                suiteName = suite.fullyQualifiedName,
                symbol = suite.symbol,
              )
            case Config.TestFramework.TestNG =>
              testNGTestFinder.findTests(
                doc = semanticdb,
                path = path,
                suiteSymbol = suite.symbol,
              )
            case TestFrameworkUtils.ZioTestFramework =>
              zioTestFinder.findTests(
                path = path,
                suiteName = suite.fullyQualifiedName,
              )
            case _ => Vector.empty
          }

          if (testCases.nonEmpty) {
            index.updateFileMetadata(path, semanticdb.md5)
            val event = AddTestCases(
              fullyQualifiedClassName = suite.fullyQualifiedName.value,
              className = suite.className.value,
              testCases = testCases.asJava,
            )
            Some(event)
          } else None
        }
      }
      .getOrElse(Vector.empty)
  }

  /**
   * Find test suites for all build targets in current projects and update caches.
   */
  private def doRefreshTestSuites(): Future[Unit] = Future {
    val symbolsPerTarget = buildTargets.allBuildTargetIds.toList
      // filter out JS and Native platforms
      .filter { id =>
        buildTargets
          .scalaTarget(id)
          .forall(_.scalaInfo.getPlatform == ScalaPlatform.JVM)
      }
      .flatMap(buildTargets.info)
      // filter out sbt builds
      .filterNot(_.isSbtBuild)
      .map { buildTarget =>
        SymbolsPerTarget(
          buildTarget,
          buildTargetClasses.classesOf(buildTarget.getId).testClasses,
        )
      }

    val deletedSuites = removeStaleTestSuites(symbolsPerTarget)
    val addedEntries = getTestEntries(symbolsPerTarget)

    // update cached suites with currently discovered
    addedEntries.foreach { case (_, entries) =>
      entries.foreach(index.put(_))
    }

    if (isCodeLensEnabled) client.refreshModel()

    if (isExplorerEnabled) {
      val addedTestCases = addedEntries.mapValues {
        _.flatMap { entry =>
          val canResolve =
            TestFrameworkUtils.canResolveTests(entry.suiteDetails.framework)
          if (canResolve && buffers.contains(entry.path))
            getTestCasesForSuites(entry.path, Vector(entry.suiteDetails), None)
          else Nil
        }
      }.toMap

      val addedSuites =
        addedEntries.mapValues(_.map(_.suiteDetails.asAddEvent)).toMap

      val buildTargetUpdates =
        getBuildTargetUpdates(
          deletedSuites = deletedSuites,
          addedSuites = addedSuites,
          addedTestCases = addedTestCases,
        )

      updateClientIfNonEmpty(buildTargetUpdates)
    }
  }

  /**
   * BSP server returns fully qualified names of all test suites per build target.
   * Remove cached entries which were deleted (not returned by BSP).
   */
  private def removeStaleTestSuites(
      symbolsPerTargets: List[SymbolsPerTarget]
  ): Map[BuildTarget, List[TestExplorerEvent]] = {
    val removedBuildTargets =
      index.allSuites.map(_._1).toSet -- symbolsPerTargets.map(_.target)
    // first we want to send information about deleted build targets
    val symbolsPerTargetsWithEmpty =
      symbolsPerTargets ++ removedBuildTargets.map(
        SymbolsPerTarget(_, TrieMap.empty)
      )
    // when test suite is deleted it has to be removed from cache
    symbolsPerTargetsWithEmpty.map {
      case SymbolsPerTarget(buildTarget, testSymbols) =>
        val fromBSP =
          testSymbols.values
            .map(info => FullyQualifiedName(info.fullyQualifiedName))
            .toSet
        val cached = index.getSuiteNames(buildTarget)
        val diff = (cached -- fromBSP)
        val removed = diff.foldLeft(List.empty[TestExplorerEvent]) {
          case (deleted, unusedClassName) =>
            index.remove(buildTarget, unusedClassName) match {
              case Some(entry) => entry.suiteDetails.asRemoveEvent :: deleted
              case None => deleted
            }
        }
        (buildTarget, removed)
    }.toMap
  }

  /**
   * Discover test entries per all known build targets.
   * Once discovered, test entry is put in the cache.
   */
  private def getTestEntries(
      symbolsPerTarget: List[SymbolsPerTarget]
  ): Map[BuildTarget, List[TestEntry]] = {
    val entries = symbolsPerTarget.flatMap { currentTarget =>
      // index will be updated later
      val currentlyCached = index.getSuiteNames(currentTarget.target)
      val cachedSuites = mutable.Set.from(currentlyCached)
      currentTarget.testSymbols
        .readOnlySnapshot()
        .toList
        // sort the symbols lexically so that symbols with the same fullyQualifiedName
        // will be grouped, and the class will come before companion object (i.e.
        // `a.b.WordSpec#` < `a.b.WordSpec.`). This ensures that the class is put into the cache
        // instead of the companion object.
        .sortBy { case (symbol, _) => symbol }
        .foldLeft(List.empty[TestEntry]) {
          case (entries, (symbol, testSymbolInfo)) =>
            val fullyQualifiedName =
              FullyQualifiedName(testSymbolInfo.fullyQualifiedName)
            if (cachedSuites.contains(fullyQualifiedName)) entries
            else {
              val entryOpt = computeTestEntry(
                currentTarget.target,
                mtags.Symbol(symbol),
                fullyQualifiedName,
                testSymbolInfo,
              )
              entryOpt match {
                case Some(entry) =>
                  cachedSuites.add(entry.suiteDetails.fullyQualifiedName)
                  entry :: entries
                case None => entries
              }
            }
        }
    }

    entries.groupBy(_.buildTarget)
  }

  /**
   * Compute BuildTargetUpdates from added and deleted entries.
   * Order of events in build target update: delete suite, add suite, add test cases.
   */
  private def getBuildTargetUpdates(
      deletedSuites: Map[BuildTarget, List[TestExplorerEvent]],
      addedSuites: Map[BuildTarget, List[TestExplorerEvent]],
      addedTestCases: Map[BuildTarget, List[TestExplorerEvent]],
  ): List[BuildTargetUpdate] = {
    // because events are being prepended, iterate through them in reversed order
    // (testcases, add, remove)
    val aggregated =
      (addedTestCases.toIterator ++ addedSuites.toIterator ++ deletedSuites.toIterator)
        .foldLeft(Map.empty[BuildTarget, List[TestExplorerEvent]]) {
          case (acc, (target, events)) =>
            val prev = acc.getOrElse(target, List.empty)
            acc.updated(target, events ++ prev)
        }
        .filter { case (_, events) => events.nonEmpty }

    aggregated.map { case (target, events) =>
      buildTargetUpdate(target, events)
    }.toList
  }

  /**
   * Get test entry for the given (builTarget, symbol).
   */
  private def computeTestEntry(
      buildTarget: BuildTarget,
      symbol: mtags.Symbol,
      fullyQualifiedName: FullyQualifiedName,
      testSymbolInfo: BuildTargetClasses.TestSymbolInfo,
  ): Option[TestEntry] = {
    val symbolDefinition = symbolIndex
      .definition(symbol)
      // check if definition symbol is equal to queried symbol
      .filter(_.definitionSymbol == symbol)

    val entryOpt = symbolDefinition
      .map { definition =>
        val location = definition.range
          .getOrElse(semanticdb.Range.defaultInstance)
          .toLocation(definition.path.toURI.toString())

        val className = fullyQualifiedName.value.split('.').last

        val suiteInfo = TestSuiteDetails(
          fullyQualifiedName = fullyQualifiedName,
          framework = testSymbolInfo.framework,
          className = ClassName(className),
          symbol = symbol,
          location = location,
        )

        TestEntry(
          buildTarget = buildTarget,
          path = definition.path,
          suiteDetails = suiteInfo,
        )
      }
    entryOpt
  }

  private def buildTargetUpdate(
      buildTarget: BuildTarget,
      events: Seq[TestExplorerEvent],
  ): BuildTargetUpdate =
    BuildTargetUpdate(
      buildTarget,
      folderName,
      folderUri,
      events,
    )

  def getFramework(
      target: BuildTarget,
      selection: ScalaTestSuiteSelection,
  ): Config.TestFramework = getFromCache(target, selection.className)
    .map(_.suiteDetails.framework)
    .getOrElse(Config.TestFramework(Nil))

  def getFromCache(
      target: BuildTarget,
      className: String,
  ): Option[TestEntry] = {
    index.get(target, FullyQualifiedName(className))
  }

}
