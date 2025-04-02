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
import ch.epfl.scala.debugadapter.testing.TestSuiteSummary
import ch.epfl.scala.{bsp4j => b}

class McpTestRunner(
    debugProvider: DebugProvider,
    buildTargets: BuildTargets,
    workspace: AbsolutePath,
    userConfig: () => UserConfiguration,
)(implicit ec: ExecutionContext) {
  def runTests(
      path: AbsolutePath,
      testClass: String,
  ): Either[String, Future[String]] = {
    val buildTarget =
      buildTargets.sourceBuildTargets(path).flatMap(_.headOption)
    val testSuites = new b.ScalaTestSuites(
      List(
        new b.ScalaTestSuiteSelection(testClass, Nil.asJava)
      ).asJava,
      Nil.asJava,
      Nil.asJava,
    )
    val cancelPromise = Promise[Unit]()
    for {
      id <- buildTarget.toRight(s"Missing build target in debug params.")
      projectInfo <- debugProvider.debugConfigCreator.create(id, cancelPromise)
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
        listner = new McpDebuggeeListener()
        _ <- adapter.run(listner).future
      } yield listner.result
    }
  }
}

class McpDebuggeeListener() extends DebuggeeListener {
  private val buffer = new StringBuffer()
  override def onListening(address: InetSocketAddress): Unit = {}

  override def out(line: String): Unit = buffer.append(line).append("\n")

  override def err(line: String): Unit = buffer.append(line).append("\n")

  override def testResult(data: TestSuiteSummary): Unit = {}

  def result: String = AnsiFilter()(buffer.toString())
}
