package scala.meta.internal.metals.mcp

import java.net.InetSocketAddress

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise

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
  def runTests(
      testClass: String,
      optPath: Option[AbsolutePath],
      testName: Option[String],
      verbose: Boolean,
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
      result <-
        if (debugProvider.usesBuildTargetTest(id)) {
          // Same path as non-debug editor runs for MBT: BSP buildTarget/test.
          Right {
            jvmTestEnv.flatMap { env =>
              val testSuites = createTestSuites(testSelection, env)
              debugProvider.runBuildTargetTest(
                id,
                testSuites,
                cancelPromise,
                verbose,
              ) match {
                case Right(future) => future.map(output => AnsiFilter()(output))
                case Left(error) => Future.successful(error)
              }
            }
          }
        } else {
          runLocally(
            id,
            path,
            testSelection,
            jvmTestEnv,
            cancelPromise,
            verbose,
          )
        }
    } yield result
  }

  private def createTestSuites(
      testSelection: b.ScalaTestSuiteSelection,
      env: Option[b.JvmEnvironmentItem],
  ): b.ScalaTestSuites = {
    val settings = DebugProvider.scalaTestLocalRunSettings(workspace, env)
    new b.ScalaTestSuites(
      List(testSelection).asJava,
      settings.jvmOptions.asJava,
      settings.environmentVariablesAsStrings.asJava,
    )
  }

  private def runLocally(
      id: b.BuildTargetIdentifier,
      path: AbsolutePath,
      testSelection: b.ScalaTestSuiteSelection,
      jvmTestEnv: Future[Option[b.JvmEnvironmentItem]],
      cancelPromise: Promise[Unit],
      verbose: Boolean,
  ): Either[String, Future[String]] = {
    for {
      projectFut <- debugProvider.createDebugeeProjectForTests(
        id,
        cancelPromise,
        jvmTestEnv,
      )
    } yield {
      for {
        _ <- compilations.compileFile(path)
        env <- jvmTestEnv
        testSuites = createTestSuites(testSelection, env)
        discovered <- debugProvider.discoverTests(id, testSuites)
        project <- projectFut
        listener = new McpDebuggeeListener(verbose)
        adapter = new TestSuiteDebugAdapter(
          workspace,
          testSuites,
          project,
          userConfig().javaHome,
          discovered,
          isDebug = false,
        )
        _ <- adapter.run(listener).future
      } yield listener.result
    }
  }

  private def resolvePath(fqcn: String): Option[AbsolutePath] = {
    mcpSearch.exactSearch(fqcn, None).flatMap(_.definitionPath).headOption
  }
}

class McpDebuggeeListener(verbose: Boolean) extends DebuggeeListener {
  private val buffer = new StringBuffer()
  override def onListening(address: InetSocketAddress): Unit = {}

  override def out(line: String): Unit =
    if (verbose) buffer.append(line).append("\n")

  override def err(line: String): Unit = buffer.append(line).append("\n")

  override def testResult(data: TestSuiteSummary): Unit =
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
  def result: String = AnsiFilter()(buffer.toString())
}
