package scala.meta.internal.metals.testProvider

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.BatchedFunction
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.ClientCommands
import scala.meta.internal.metals.ClientConfiguration
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.SemanticdbFeatureProvider
import scala.meta.internal.metals.TestUserInterfaceKind
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.debug.BuildTargetClasses
import scala.meta.internal.metals.testProvider.TestExplorerEvent._
import scala.meta.internal.metals.testProvider.frameworks.JunitTestFinder
import scala.meta.internal.mtags
import scala.meta.internal.mtags.GlobalSymbolIndex
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.semanticdb
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.semanticdb.TextDocuments
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTarget
import ch.epfl.scala.bsp4j.ScalaPlatform

final class TestSuitesProvider(
    buildTargets: BuildTargets,
    buildTargetClasses: BuildTargetClasses,
    symbolIndex: GlobalSymbolIndex,
    semanticdbs: Semanticdbs,
    buffers: Buffers,
    clientConfig: ClientConfiguration,
    userConfig: () => UserConfiguration,
    client: MetalsLanguageClient
)(implicit ec: ExecutionContext)
    extends SemanticdbFeatureProvider {

  private val index = new TestSuitesIndex
  private val junitTestFinder = new JunitTestFinder

  private def isEnabled =
    clientConfig.isTestExplorerProvider() &&
      userConfig().testUserInterface == TestUserInterfaceKind.TestExplorer

  val refreshTestSuites: BatchedFunction[Unit, Unit] =
    BatchedFunction.fromFuture { _ =>
      if (isEnabled) doRefreshTestSuites()
      else Future.unit
    }

  /**
   * Update test cases for given path.
   * Only consider 'relevant' files which have had already discovered test suite.
   * Metals have to update them because they can be visible to the user via:
   * 1. Test Explorer view can be opened and tree view is visible
   * 2. test suite's file can be opened and test cases are visible
   */
  override def onChange(docs: TextDocuments, file: AbsolutePath): Unit = {
    if (isEnabled && index.hasTestCasesGranularity(file)) {
      if (docs.documents.nonEmpty) {
        val doc = docs.documents.headOption
        val buildTargetUpdates = getTestCasesForPath(file, doc)
        updateClientIfNonEmpty(buildTargetUpdates)
      }
    }
  }

  override def onDelete(file: AbsolutePath): Unit = {
    val removed = index.remove(file)
    val removeEvents = removed
      .groupBy(_.buildTarget)
      .map { case (buildTarget, entries) =>
        BuildTargetUpdate(buildTarget, entries.map(_.testClass.asRemove))
      }
      .toList

    updateClientIfNonEmpty(removeEvents)
  }

  override def reset(): Unit = ()

  /**
   * Check if opened file contains test suite and update test cases if yes.
   */
  def didOpen(file: AbsolutePath): Future[Unit] =
    if (isEnabled && index.contains(file)) Future {
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
  ): java.util.List[BuildTargetUpdate] = {
    val updates = path match {
      case Some(path0) => getTestCasesForPath(path0, None)
      case None => getTestSuites()
    }
    updates.asJava
  }

  private def updateClientIfNonEmpty(updates: Seq[BuildTargetUpdate]): Unit =
    if (updates.nonEmpty) {
      val params =
        ClientCommands.UpdateTestExplorer.toExecuteCommandParams(updates: _*)
      client.metalsExecuteClientCommand(params)
    }

  /**
   * Retrieves all cached test suites.
   */
  private def getTestSuites(): Seq[BuildTargetUpdate] = {
    index.allSuites.map { case (buildTarget, entries) =>
      BuildTargetUpdate(buildTarget, entries.map(_.testClass).toSeq)
    }.toSeq
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
      textDocument: Option[TextDocument]
  ): List[BuildTargetUpdate] = {
    val buildTargetUpdates =
      for {
        metadata <- index.getMetadata(path).toList
        events = {
          val suites = metadata.entries.map(_.suiteInfo).distinct
          getTestCasesForSuites(path, suites, textDocument)
        }
        buildTarget <- metadata.entries.map(_.buildTarget).distinct
      } yield {
        BuildTargetUpdate(buildTarget, events)
      }
    buildTargetUpdates
  }

  /**
   * Searches for test cases for a given path for a provided test suites.
   */
  private def getTestCasesForSuites(
      path: AbsolutePath,
      suites: Seq[TestSuiteInfo],
      textDocument: Option[TextDocument]
  ): Seq[AddTestCases] =
    for {
      // if text document isn't defined try to fetch it from semanticdbs
      doc <- textDocument
        .orElse(semanticdbs.textDocument(path).documentIncludingStale)
        .toSeq
      suite <- suites
    } yield {
      val testCases = junitTestFinder.findTests(doc, path, suite.symbol)

      if (testCases.nonEmpty) {
        index.setHasTestCasesGranularity(path)
      }

      AddTestCases(
        suite.fullyQualifiedName.value,
        suite.className.value,
        testCases.asJava
      )
    }

  /**
   * Find test suites for all build targets in current projects and update caches.
   */
  private def doRefreshTestSuites(): Future[Unit] = Future {
    val symbolsPerTarget = buildTargets.allBuildTargetIds.toList
      // filter out JS and Native platforms
      .filter(id =>
        buildTargets
          .scalaTarget(id)
          .forall(_.scalaInfo.getPlatform == ScalaPlatform.JVM)
      )
      .flatMap(buildTargets.info)
      .filterNot(_.isSbtBuild)
      .map { buildTarget =>
        val scalac = buildTargets
          .scalaTarget(buildTarget.getId)
          .map(_.scalac)
        val javac = buildTargets
          .javaTarget(buildTarget.getId)
          .map(_.javac)
        SymbolsPerTarget(
          buildTarget,
          buildTargetClasses.classesOf(buildTarget.getId).testClasses,
          scalac,
          javac
        )
      }

    val deletedSuites = removeStaleTestSuites(symbolsPerTarget)
    val addedEntries = getTestEntries(symbolsPerTarget)

    val addedTestCases = addedEntries.mapValues {
      _.flatMap { entry =>
        if (buffers.contains(entry.path))
          getTestCasesForSuites(entry.path, List(entry.suiteInfo), None)
        else Seq.empty
      }
    }

    val addedSuites = addedEntries.mapValues(_.map(_.testClass))

    val buildTargetUpdates =
      getBuildTargetUpdates(deletedSuites, addedSuites, addedTestCases)

    // update cached suites with currently discovered
    addedEntries.foreach { case (_, entries) =>
      entries.foreach(index.put(_))
    }
    updateClientIfNonEmpty(buildTargetUpdates)
  }

  /**
   * BSP server returns fully qualified names of all test suites per build target.
   * Remove cached entries which were deleted (not returned by BSP).
   */
  private def removeStaleTestSuites(
      symbolsPerTargets: List[SymbolsPerTarget]
  ): Map[BuildTarget, List[TestExplorerEvent]] = {
    // when test suite is deleted it has to be removed from cache
    symbolsPerTargets.map {
      case SymbolsPerTarget(buildTarget, testSymbols, _) =>
        val fromBSP = testSymbols.values.toSet.map(FullyQualifiedName)
        val cached = index.getSuiteNames(buildTarget)
        val diff = (cached -- fromBSP)
        val removed = diff.foldLeft(List.empty[TestExplorerEvent]) {
          case (deleted, unusedClassName) =>
            index.remove(buildTarget, unusedClassName) match {
              case Some(entry) => entry.testClass.asRemove :: deleted
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
      val cachedSuites = mutable.Set.empty[FullyQualifiedName]
      currentTarget.testSymbols
        .readOnlySnapshot()
        .toList
        .foldLeft(List.empty[TestEntry]) {
          case (entries, (symbol, fullyQualifiedClassName)) =>
            val fullyQualifiedName = FullyQualifiedName(fullyQualifiedClassName)
            if (cachedSuites.contains(fullyQualifiedName)) entries
            else {
              val entryOpt = computeTestEntry(
                currentTarget.target,
                mtags.Symbol(symbol),
                fullyQualifiedName,
                currentTarget.hasJunitOnClasspath
              )
              entryOpt match {
                case Some(entry) =>
                  cachedSuites.add(entry.suiteInfo.fullyQualifiedName)
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
      addedTestCases: Map[BuildTarget, List[TestExplorerEvent]]
  ): Seq[BuildTargetUpdate] = {
    // because events are being prepended, iterate through them in reversed order
    // (testcases, add, remove)
    val aggregated =
      (addedTestCases.toIterator ++ addedSuites.toIterator ++ deletedSuites.toIterator)
        .foldLeft(Map.empty[BuildTarget, List[TestExplorerEvent]]) {
          case (acc, (target, events)) =>
            val prev = acc.getOrElse(target, List.empty)
            acc.updated(target, events ++ prev)
        }
        .filter(_._2.nonEmpty)

    aggregated.map { case (target, events) =>
      BuildTargetUpdate(target, events)
    }.toSeq
  }

  /**
   * Get test entry for the given (builTarget, symbol).
   * @param hasJunitOnClasspath - for now only junit test classes can resolve
   */
  private def computeTestEntry(
      buildTarget: BuildTarget,
      symbol: mtags.Symbol,
      fullyQualifiedName: FullyQualifiedName,
      hasJunitOnClasspath: Boolean
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

        val testClass = AddTestSuite(
          fullyQualifiedName.value,
          className,
          symbol.value,
          location,
          canResolveChildren = hasJunitOnClasspath
        )

        val suiteInfo = TestSuiteInfo(
          fullyQualifiedName,
          ClassName(className),
          symbol
        )

        TestEntry(
          buildTarget,
          definition.path,
          suiteInfo,
          testClass
        )
      }
    entryOpt
  }

}
