package tests.mill

import java.net.URI

import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration._

import scala.meta.internal.builds.MillBuildTool
import scala.meta.internal.builds.MillDigest
import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.metals.DebugDiscoveryParams
import scala.meta.internal.metals.DebugSession
import scala.meta.internal.metals.DebugUnresolvedAttachRemoteParams
import scala.meta.internal.metals.JdkSources
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.ScalaTestSuiteSelection
import scala.meta.internal.metals.ScalaTestSuites
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.StatusBarConfig
import scala.meta.internal.metals.clients.language.StatusType
import scala.meta.internal.metals.debug.Stoppage
import scala.meta.internal.metals.debug.TestDebugger
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.DebugSessionParamsDataKind
import ch.epfl.scala.bsp4j.ScalaMainClass
import ch.epfl.scala.bsp4j.TestParamsDataKind
import tests.BaseImportSuite
import tests.BaseMillServerSuite
import tests.JavaHomeChangeTest
import tests.MillBuildLayout
import tests.MillServerInitializer

/**
 * Basic suite to ensure that a connection to a Mill server can be made.
 */
class MillServerSuite
    extends BaseImportSuite("mill-server", MillServerInitializer)
    with JavaHomeChangeTest
    with BaseMillServerSuite {

  override def importBuildMessage: String =
    Messages.GenerateBspAndConnect
      .params(
        MillBuildTool.name,
        MillBuildTool.bspName,
      )
      .getMessage

  val preBspVersion = "0.9.10"
  val supportedBspVersion = V.millVersion
  val scalaVersion = V.scala213
  def buildTool: MillBuildTool = MillBuildTool(() => userConfig, workspace)
  override def serverConfig: MetalsServerConfig =
    super.serverConfig.copy(statusBar = StatusBarConfig.on)

  override def currentDigest(
      workspace: AbsolutePath
  ): Option[String] = MillDigest.current(workspace)

  override def afterEach(context: AfterEach): Unit = {
    super.afterEach(context)
    killMillServer(workspace)
  }
  override def beforeEach(context: BeforeEach): Unit = {
    super.beforeEach(context)
    cleanWorkspace()
    bspTrace.touch()
  }

  test("basic-1.0.0") {
    cleanWorkspace()
    writeLayout(
      MillBuildLayout(
        """|/a/src/main.scala
           |object Failure {
           |  def scalaVersion: String = 3
           |}
           |""".stripMargin,
        V.latestScala3Next,
        testDep = None,
        V.millVersion,
      )
    )
    def millBspConfig = workspace.resolve(".bsp/mill-bsp.json")
    client.generateBspAndConnect = Messages.GenerateBspAndConnect.yes
    for {
      _ <- server.initialize()
      _ <- server.initialized()
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        Messages.GenerateBspAndConnect
          .params(
            MillBuildTool.name,
            MillBuildTool.bspName,
          )
          .getMessage,
      )
      _ <- server.headServer.buildServerPromise.future
      _ = assert(millBspConfig.exists)
      _ <- server.didSave("a/src/main.scala")
    } yield {
      assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main.scala:2:30: error: Found:    (3 : Int)
           |Required: String
           |  def scalaVersion: String = 3
           |                             ^
           |""".stripMargin,
      )
    }
  }

  test("too-old") {
    writeLayout(MillBuildLayout("", V.scala213, testDep = None, preBspVersion))
    for {
      _ <- server.initialize()
      _ <- server.initialized()
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        List(
          super.importBuildMessage
        ).mkString("\n"),
      )
      _ = client.messageRequests.clear()
      _ <- server.executeCommand(ServerCommands.GenerateBspConfig)
    } yield {
      assertNoDiff(
        client.workspaceShowMessages,
        Messages.NoBspSupport.toString,
      )
    }
  }

  val versionsToTest: List[String] =
    List(supportedBspVersion)

  versionsToTest.foreach(testGenerationAndConnection)

  private def testGenerationAndConnection(version: String) = {
    test(s"generate-and-connect-$version") {
      def millBspConfig = workspace.resolve(".bsp/mill-bsp.json")
      writeLayout(MillBuildLayout("", V.scala213, testDep = None, version))
      for {
        _ <- server.initialize()
        _ <- server.initialized()
        _ = assertNoDiff(
          client.workspaceMessageRequests,
          // Project has no .bloop directory so user is asked to "import via bloop"
          // since bloop is still the default
          importBuildMessage,
        )
        _ = client.messageRequests.clear() // restart
        _ = assert(!millBspConfig.exists)
        // This is a little hacky but up above this promise is suceeded already, so down
        // below it won't wait until it reconnects to Mill like we want, so we set it back
        // and then it will be completed after the BSP config generation and the server
        // connects.
        _ = server.headServer.connectionProvider.buildServerPromise = Promise()
        // At this point, we want to use mill-bsp server, so create the mill-bsp.json file.
        _ <- server.executeCommand(ServerCommands.GenerateBspConfig)
        // We need to wait a bit just to ensure the connection is made
        _ <- server.server.buildServerPromise.future
      } yield {
        assert(millBspConfig.exists)
        server.assertBuildServerConnection()
      }
    }
  }

  // Looks like mill is not compiling properly here for newest version of Scala
  test(s"presentation-compiler") {
    def millBspConfig = workspace.resolve(".bsp/mill-bsp.json")
    writeLayout(
      s"""
         |/.mill-version
         |$supportedBspVersion
         |/build.sc
         |import mill._, scalalib._
         |object foo extends ScalaModule {
         |  def scalaVersion = "2.13.13"
         |}
         |/foo/src/Main.scala
         |package foo
         |
         |import foo.A
         |object Main extends App {
         |  println(A.msg)
         |}
         |
         |/foo/src/A.scala
         |package foo
         |
         |object A {
         |  def msg = "Hello"
         |}
         |""".stripMargin
    )
    for {
      _ <- server.initialize()
      _ <- server.initialized()
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        importBuildMessage,
      )
      _ = client.messageRequests.clear() // restart
      _ = assert(!millBspConfig.exists)
      _ = server.headServer.connectionProvider.buildServerPromise = Promise()
      _ <- server.executeCommand(ServerCommands.GenerateBspConfig)
      _ <- server.server.buildServerPromise.future
      _ = assert(millBspConfig.exists)
      _ = server.assertBuildServerConnection()
      _ <- server.didOpen("foo/src/Main.scala")
      _ <- server.assertHoverAtLine(
        "foo/src/Main.scala",
        "println(A.m@@sg)",
        """|```scala
           |def msg: String
           |```
           |""".stripMargin,
      )
    } yield {}
  }

  checkJavaHomeUpdate(
    "mill-java-home-update",
    fileContent => s"""|/build.sc
                       |import mill._, scalalib._
                       |object a extends ScalaModule {
                       |  def scalaVersion = "${V.scala213}"
                       |}
                       |$fileContent
                       |""".stripMargin,
  )

  test(s"no-ide-modules-are-not-loaded") {
    def millBspConfig = workspace.resolve(".bsp/mill-bsp.json")
    writeLayout(
      s"""
         |/.mill-version
         |$supportedBspVersion
         |/build.mill
         |package build
         |import mill.*
         |import scalalib.*
         |  
         |import mill.api.daemon.internal.bsp.BspBuildTarget
         |import mill.api.daemon.internal.bsp.BspModuleApi
         |  
         |object foo extends ScalaModule {
         |  def scalaVersion = "2.13.13"
         |}
         |  
         |object bar extends ScalaModule   {
         |  def scalaVersion = "2.13.13"
         |
         |   override def bspBuildTarget: BspBuildTarget = super.bspBuildTarget.copy(
         |     tags = super.bspBuildTarget.tags ++ Seq(BspModuleApi.Tag.NoIDE),
         |   )
         |}
         |/foo/src/Main.scala
         |package foo
         |
         |object Main extends App {
         |  println("Hello, world!")
         |}
         |
         |/bar/src/Main.scala
         |package foo
         |
         |object Main extends App {
         |  println("Hello, world!")
         |}
         |""".stripMargin
    )
    for {
      _ <- server.initialize()
      _ <- server.initialized()
      _ <- server.executeCommand(ServerCommands.GenerateBspConfig)
      _ <- server.server.buildServerPromise.future
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        importBuildMessage,
      )
      targets <- server.listBuildTargets
    } yield {
      assert(millBspConfig.exists)
      server.assertBuildServerConnection()
      assertEquals(targets, List("foo", "mill-build/"))
    }
  }

  test("call pc before initial compilation".flaky) {
    cleanWorkspace()
    client.getStatusParams(StatusType.metals).clear()
    val compileTaskFinished = Promise[Unit]()
    client.onMetalsStatus = { params =>
      if (params.text.contains("Compiled")) compileTaskFinished.success(())
    }
    for {
      _ <- initialize(
        s"""|/build.sc
            |import mill._, scalalib._
            |object foo extends ScalaModule {
            |  def scalaVersion = "${V.scala213}"
            |}
            |/foo/src/bar/Main.scala
            |package bar
            |object Main {
            |  val i: Int = "aaaa"
            |}
            |/foo/src/bar/Foo.scala
            |package bar
            |object Foo {
            |  def foo = "foo"
            |}
            |""".stripMargin
      )
      res1 <- server.info("foo/src/bar/Main.scala", "bar/Foo.")
      _ = assert(res1.isEmpty)
      _ <- server.didOpen("foo/src/bar/Main.scala")
      _ <- server.didChange("foo/src/bar/Main.scala") { _ =>
        """|package bar
           |object Main {
           |  val i: Int = 1
           |}
           |""".stripMargin
      }
      _ <- server.didSave("foo/src/bar/Main.scala")
      _ <- compileTaskFinished.future
      res2 <- server.info("foo/src/bar/Main.scala", "bar/Foo.")
      _ = assert(res2.isDefined)
    } yield ()
  }

  // https://github.com/scalameta/metals/pull/7544
  test("debug-passes-environment-variables") {
    cleanWorkspace()
    writeLayout(
      s"""
         |/build.mill
         |//| mill-version: ${V.millVersion}
         |package build
         |import mill.*, scalalib.*
         |
         |object foo extends ScalaModule {
         |  def scalaVersion = "${V.scala213}"
         |
         |  def forkEnv = Map("MY_ENV" -> "MY_VALUE")
         |}
         |/foo/src/app/Main.scala
         |package app
         |object Main {
         |  def main(args: Array[String]): Unit = {
         |    println(sys.env.get("MY_ENV"))
         |    System.exit(0)
         |  }
         |}
         |""".stripMargin
    )
    for {
      _ <- initMillBsp()
      output <- runMillDebugger(
        server.startDebugging(
          "foo",
          DebugSessionParamsDataKind.SCALA_MAIN_CLASS,
          new ScalaMainClass("app.Main", Nil.asJava, Nil.asJava),
        )
      )
    } yield assertNoDiff(output, "Some(MY_VALUE)")
  }

  // https://github.com/scalameta/metals/pull/7544
  test("passing-test-environment-variables") {
    cleanWorkspace()
    writeLayout(
      s"""|/build.mill
          |//| mill-version: ${V.millVersion}
          |package build
          |import mill.*, scalalib.*
          |
          |object foo extends ScalaModule {
          |  def scalaVersion = "3.7.0"
          |
          |  override def forkEnv = Map("DOGGIES" -> "main")
          |
          |  object test extends ScalaTests with TestModule.Munit {
          |    def mvnDeps = Seq(
          |      mvn"org.scalameta::munit::1.1.1"
          |    )
          |
          |    def forkEnv = super.forkEnv() ++ Map("DOGGIES" -> "tests")
          |  }
          |}
          |/foo/test/src/FooMUnitTests.scala
          |package foo
          |
          |import munit.FunSuite
          |
          |class FooMUnitTests extends FunSuite {
          |  test("env var") {
          |    assertEquals(sys.env.get("DOGGIES"), Some("tests"))
          |  }
          |}
          |""".stripMargin
    )
    for {
      _ <- initMillBsp()
      output <- runMillDebugger(
        server.startDebugging(
          "foo.test",
          TestParamsDataKind.SCALA_TEST_SUITES_SELECTION,
          ScalaTestSuites(
            List(
              ScalaTestSuiteSelection("foo.FooMUnitTests", Nil.asJava)
            ).asJava,
            Nil.asJava,
            Nil.asJava,
          ),
        )
      )
    } yield assert(
      output.contains("All tests in foo.FooMUnitTests passed"),
      clue = output,
    )
  }

  test("attach") {
    val port = 5566
    def runningMain() = Future {
      val classpathJar = workspace.resolve(".metals/.tmp").list.head.toString()
      ShellRunner.runSync(
        List(
          JdkSources.defaultJavaHome(None).head.resolve("bin/java").toString,
          "-Dproperty=Foo",
          s"-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=$port",
          "-cp",
          classpathJar,
          "bar.Main",
          "Bar",
        ),
        workspace,
        true,
        Map("HELLO" -> "Foo"),
        propagateError = true,
        timeout = 60.seconds,
      )
    }

    for {
      _ <- initialize(
        s"""|/build.sc
            |import mill._, scalalib._
            |object foo extends ScalaModule {
            |  def scalaVersion = "${V.scala213}"
            |}
            |/foo/src/bar/Main.scala
            |package bar
            |object Main {
            |  def main(args: Array[String]) = {
            |    val foo = sys.props.getOrElse("property", "")
            |    val bar = args(0)
            |    val env = sys.env.get("HELLO")
            |    print(foo + bar)
            |    env.foreach(print)
            |    System.exit(0)
            |  }
            |}
            |""".stripMargin
      )
      _ <- server.headServer.buildServerPromise.future
      _ <- server.didOpen("foo/src/bar/Main.scala")
      _ <- server.didSave("foo/src/bar/Main.scala")
      // creates a classpath jar that we can use
      _ <- server.executeDiscoverMainClassesCommand(
        new DebugDiscoveryParams(
          null,
          "run",
          "bar.Main",
        )
      )
      runMain = runningMain()
      debugSession <- server.executeCommand(
        ServerCommands.StartAttach,
        DebugUnresolvedAttachRemoteParams("localhost", port),
      )
      debugger = debugSession match {
        case DebugSession(_, uri) =>
          scribe.info(s"Starting debug session for $uri")
          TestDebugger(
            URI.create(uri),
            Stoppage.Handler.Continue,
            requestOtherThreadStackTrace = false,
          )
        case _ => throw new RuntimeException("Debug session not found")
      }
      _ <- debugger.initialize
      _ <- debugger.attach(port)
      _ <- debugger.configurationDone
      output <- runMain
      _ <- debugger.disconnect
      _ <- debugger.shutdown
      _ <- debugger.allOutput
    } yield assertNoDiff(
      output.getOrElse(""),
      s"""|Listening for transport dt_socket at address: $port
          |FooBarFoo""".stripMargin,
    )
  }

  private def initMillBsp(): Future[Unit] = {
    def millBspConfig = workspace.resolve(".bsp/mill-bsp.json")
    client.generateBspAndConnect = Messages.GenerateBspAndConnect.yes
    for {
      _ <- server.initialize()
      _ <- server.initialized()
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        Messages.GenerateBspAndConnect
          .params(
            MillBuildTool.name,
            MillBuildTool.bspName,
          )
          .getMessage,
      )
      _ <- server.headServer.buildServerPromise.future
      _ = assert(millBspConfig.exists)
    } yield ()
  }

  private def runMillDebugger(f: => Future[TestDebugger]): Future[String] = {
    for {
      debugger <- f
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
      _ = server.server.cancel()
    } yield output
  }
}
