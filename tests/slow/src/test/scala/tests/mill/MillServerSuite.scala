package tests.mill

import scala.concurrent.Promise

import scala.meta.internal.builds.MillBuildTool
import scala.meta.internal.builds.MillDigest
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.ScalaTestSuiteSelection
import scala.meta.internal.metals.ScalaTestSuites
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.StatusBarConfig
import scala.meta.internal.metals.clients.language.StatusType
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.io.AbsolutePath

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
        "1.0.0-RC1",
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
          importBuildMessage
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
    List("0.11.13", supportedBspVersion)

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
         |/build.sc
         |package build
         |import mill.scalalib.bsp.BspBuildTarget
         |import mill.scalalib.bsp.BspModule
         |  
         |import mill._
         |import scalalib._
         |  
         |object foo extends ScalaModule {
         |  def scalaVersion = "2.13.13"
         |}
         |  
         |object bar extends ScalaModule with BspModule  {
         |  def scalaVersion = "2.13.13"
         |  
         |  override def bspBuildTarget: BspBuildTarget = {
         |    val original = super.bspBuildTarget
         |    original.copy(tags = original.tags :+ BspModule.Tag.NoIDE)
         |  }
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

  test("call pc before initial compilation") {
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
      res1 <- server.headServer.compilers
        .info(server.toPath("foo/src/bar/Main.scala"), "bar/Foo.")
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
      res2 <- server.headServer.compilers
        .info(server.toPath("foo/src/bar/Main.scala"), "bar/Foo.")
      _ = assert(res2.isDefined)
    } yield ()
  }

  // https://github.com/com-lihaoyi/mill/issues/5039
  test("passing-test-environment-variables") {
    cleanWorkspace()
    writeLayout(
      """|/build.mill
         |//| mill-version: 1.0.0-RC1
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
      debugger <- server.startDebugging(
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
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
      _ = server.server.cancel()
    } yield assert(
      output.contains("All tests in foo.FooMUnitTests passed"),
      clue = output,
    )
  }

}
