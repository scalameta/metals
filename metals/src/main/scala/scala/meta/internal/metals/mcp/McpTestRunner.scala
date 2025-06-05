package scala.meta.internal.metals.mcp

import java.net.InetSocketAddress

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise

import scala.meta.internal.ansi.AnsiFilter
import scala.meta.internal.metals.BuildTargets
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
    workspace: AbsolutePath,
    userConfig: () => UserConfiguration,
    mcpSearch: McpSymbolSearch,
)(implicit ec: ExecutionContext) {
  def runTests(
      testClass: String,
      optPath: Option[AbsolutePath],
      verbose: Boolean,
  ): Either[String, Future[String]] = {
    val testSuites = new b.ScalaTestSuites(
      List(
        new b.ScalaTestSuiteSelection(testClass, Nil.asJava)
      ).asJava,
      Nil.asJava,
      Nil.asJava,
    )
    val cancelPromise = Promise[Unit]()
    for {
      path <- optPath
        .orElse(resolvePath(testClass))
        .toRight(s"Missing path to test suite and failed to resolve it.")
      id <- buildTargets
        .inverseSources(path)
        .toRight(s"Could not find build target for $path")
      projectInfo <- debugProvider.debugConfigCreator.create(
        id,
        cancelPromise,
        isTests = true,
      )
    } yield {
      for {
        discovered <- debugProvider.discoverTests(id, testSuites)
        project <- projectInfo
        adapter = new TestSuiteDebugAdapter(
          workspace,
          testSuites,
          project,
          userConfig().javaHome,
          discovered,
          isDebug = false,
        )
        listener = new McpDebuggeeListener(verbose)
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
