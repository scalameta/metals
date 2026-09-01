package scala.meta.internal.metals.mcp

import java.net.InetSocketAddress

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.control.NonFatal

import scala.meta.internal.ansi.AnsiFilter
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.Compilations
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.debug.DebugProvider
import scala.meta.internal.metals.debug.server.TestSuiteDebugAdapter
import scala.meta.io.AbsolutePath

import ch.epfl.scala.debugadapter.DebuggeeListener
import ch.epfl.scala.debugadapter.testing.SingleTestResult
import ch.epfl.scala.debugadapter.testing.TestSuiteSummary
import ch.epfl.scala.{bsp4j => b}

class McpTestRunner(
    debugProvider: DebugProvider,
    buildTargets: BuildTargets,
    compilations: Compilations,
    workspace: AbsolutePath,
    userConfig: () => UserConfiguration,
    mcpSearch: McpSymbolSearch,
)(implicit ec: ExecutionContext) {
  import McpTestRunner._

  def runTests(
      testClass: String,
      optPath: Option[AbsolutePath],
      testName: Option[String],
      verbose: Boolean,
  ): Either[String, Future[String]] =
    withTestRun(testClass, optPath, testName, verbose) { runOnce =>
      runOnce().map(_.report)
    }

  def runTestsRepeated(
      testClass: String,
      optPath: Option[AbsolutePath],
      testName: Option[String],
      times: Long,
      stopOnFirstFailure: Boolean,
  ): Either[String, Future[String]] =
    if (times < 1 || times > maxRepeatTimes)
      Left(s"times must be between 1 and $maxRepeatTimes, got: $times")
    else {
      val requestedTimes = times.toInt
      withTestRun(testClass, optPath, testName, verbose = false) { runOnce =>
        def loop(
            runIndex: Int,
            acc: List[RunOutcome],
        ): Future[List[RunOutcome]] =
          if (runIndex > requestedTimes) Future.successful(acc.reverse)
          else
            runOnce()
              .recover {
                // A non-zero exit of the forked JVM (e.g. System.exit, OOM)
                // fails the run future; count it as a failed run instead of
                // aborting the whole sequence.
                case NonFatal(error) =>
                  RunOutcome(
                    failed = true,
                    s"The test run crashed: ${error.getMessage}",
                  )
              }
              .flatMap { outcome =>
                // Only the first failing run's report is ever emitted, so drop
                // the others to keep at most one report in memory.
                val keepReport = outcome.failed && !acc.exists(_.failed)
                val compact =
                  if (keepReport) outcome else outcome.copy(report = "")
                val results = compact :: acc
                if (stopOnFirstFailure && outcome.failed)
                  Future.successful(results.reverse)
                else loop(runIndex + 1, results)
              }
        loop(1, Nil).map(formatRepeatedRuns(testClass, requestedTimes, _))
      }
    }

  private def withTestRun(
      testClass: String,
      optPath: Option[AbsolutePath],
      testName: Option[String],
      verbose: Boolean,
  )(
      body: (() => Future[RunOutcome]) => Future[String]
  ): Either[String, Future[String]] = {
    val testSelection = testName match {
      case Some(name) =>
        new b.ScalaTestSuiteSelection(testClass, List(name).asJava)
      case None =>
        new b.ScalaTestSuiteSelection(testClass, Nil.asJava)
    }
    val cancelPromise = Promise[Unit]()
    for {
      path <- optPath
        .orElse(resolvePath(testClass))
        .toRight(s"Missing path to test suite and failed to resolve it.")
      id <- buildTargets
        .inverseSources(path)
        .toRight(s"Could not find build target for $path")
      jvmTestEnv = debugProvider.jvmTestEnvironment(id)
      projectFut <- debugProvider.createDebugeeProjectForTests(
        id,
        cancelPromise,
        jvmTestEnv,
      )
    } yield {
      for {
        _ <- compilations.compileFile(path)
        env <- jvmTestEnv
        settings = DebugProvider.scalaTestLocalRunSettings(workspace, env)
        testSuites = new b.ScalaTestSuites(
          List(testSelection).asJava,
          settings.jvmOptions.asJava,
          settings.environmentVariablesAsStrings.asJava,
        )
        discovered <- debugProvider.discoverTests(id, testSuites)
        project <- projectFut
        runOnce = { () =>
          val listener = new McpDebuggeeListener(verbose)
          val adapter = new TestSuiteDebugAdapter(
            workspace,
            testSuites,
            project,
            userConfig().javaHome,
            discovered,
            isDebug = false,
          )
          adapter
            .run(listener)
            .future
            .map(_ => RunOutcome(listener.runFailed, listener.result))
        }
        result <- body(runOnce)
      } yield result
    }
  }

  private def formatRepeatedRuns(
      testClass: String,
      requestedTimes: Int,
      outcomes: List[RunOutcome],
  ): String = {
    val indexed = outcomes.zipWithIndex.map { case (outcome, i) =>
      (i + 1, outcome)
    }
    val failed = indexed.filter { case (_, outcome) => outcome.failed }
    val passedCount = indexed.size - failed.size
    val timesWord =
      if (indexed.size == 1) "once" else s"${indexed.size} times"
    val failedPart =
      if (failed.isEmpty) "0 runs failed"
      else {
        val indices = failed.map { case (i, _) => i }
        val runsLabel = if (indices.size == 1) "run" else "runs"
        s"${countRuns(failed.size)} failed ($runsLabel ${indices.mkString(", ")})"
      }
    val header =
      s"Ran $testClass $timesWord: ${countRuns(passedCount)} passed, $failedPart"
    val earlyStop =
      if (indexed.size < requestedTimes)
        s"\nStopped after first failure (run ${indexed.size} of $requestedTimes)"
      else ""
    val detail = failed.headOption
      .map { case (i, outcome) =>
        s"\n--- failing run $i ---\n${outcome.report}"
      }
      .getOrElse("")
    header + earlyStop + detail
  }

  private def countRuns(n: Int): String =
    if (n == 1) "1 run" else s"$n runs"

  private def resolvePath(fqcn: String): Option[AbsolutePath] = {
    mcpSearch.exactSearch(fqcn, None).flatMap(_.definitionPath).headOption
  }
}

object McpTestRunner {
  val maxRepeatTimes: Int = 100
  private case class RunOutcome(failed: Boolean, report: String)
}

class McpDebuggeeListener(verbose: Boolean) extends DebuggeeListener {
  private val buffer = new StringBuffer()
  @volatile private var failedTestCount = 0
  @volatile private var summaryReceived = false

  override def onListening(address: InetSocketAddress): Unit = {}

  override def out(line: String): Unit =
    if (verbose) buffer.append(line).append("\n")

  override def err(line: String): Unit = buffer.append(line).append("\n")

  override def testResult(data: TestSuiteSummary): Unit = {
    summaryReceived = true
    failedTestCount += data.tests.asScala.count {
      case _: SingleTestResult.Failed => true
      case _ => false
    }
    if (!verbose) {
      val testCases = data.tests.asScala
      val grouped = testCases
        .groupBy {
          case test: SingleTestResult.Passed => test.kind
          case test: SingleTestResult.Failed => test.kind
          case test: SingleTestResult.Skipped => test.kind
        }
        .map { case (kind, tests) => (kind, tests.length) }
        .withDefaultValue(0)
      buffer.append(
        s"""|
            |${data.suiteName}:
            |${data.tests.asScala
             .map {
               case test: SingleTestResult.Passed =>
                 s"  + ${test.testName.stripPrefix(data.suiteName + ".")} passed"
               case test: SingleTestResult.Failed =>
                 s"""  x ${test.testName.stripPrefix(data.suiteName + ".")} failed:
                    |${test.error}
                    |""".stripMargin
               case test: SingleTestResult.Skipped =>
                 s"  i ${test.testName.stripPrefix(data.suiteName + ".")} skipped"
             }
             .mkString("\n")}
            |Execution took ${data.duration}ms
            |${testCases.length} tests, ${grouped("passed")} passed, ${grouped("failed")} failed, ${grouped("skipped")} skipped
            |""".stripMargin
      )
    }
  }

  /**
   * A run counts as failed when any test failed or when the suite never
   * reported a summary (e.g. it crashed before running).
   */
  def runFailed: Boolean = failedTestCount > 0 || !summaryReceived

  def result: String = AnsiFilter()(buffer.toString())
}
