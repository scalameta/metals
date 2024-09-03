package tests.mill

import scala.concurrent.Promise

import scala.meta.internal.builds.MillBuildTool
import scala.meta.internal.builds.MillDigest
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.io.AbsolutePath

import tests.BaseImportSuite
import tests.JavaHomeChangeTest
import tests.MillBuildLayout
import tests.MillServerInitializer

/**
 * Basic suite to ensure that a connection to a Mill server can be made.
 */
class MillServerSuite
    extends BaseImportSuite("mill-server", MillServerInitializer)
    with JavaHomeChangeTest {

  val preBspVersion = "0.9.10"
  val supportedBspVersion = V.millVersion
  val scalaVersion = V.scala213
  def buildTool: MillBuildTool = MillBuildTool(() => userConfig, workspace)

  override def currentDigest(
      workspace: AbsolutePath
  ): Option[String] = MillDigest.current(workspace)

  override def beforeEach(context: BeforeEach): Unit = {
    super.beforeEach(context)
    cleanWorkspace()
    bspTrace.touch()
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
    List("0.10.0", "0.10.8", supportedBspVersion)

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

}
