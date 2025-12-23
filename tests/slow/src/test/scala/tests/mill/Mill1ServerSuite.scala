package tests.mill

import scala.meta.internal.builds.MillBuildTool
import scala.meta.internal.builds.MillDigest
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.StatusBarConfig
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.io.AbsolutePath

import tests.BaseImportSuite
import tests.BaseMillServerSuite
import tests.JavaHomeChangeTest
import tests.MillBuildLayout
import tests.MillServerInitializer

/**
 * Basic suite to ensure that a connection to a Mill server can be made.
 */
class Mill1ServerSuite
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

  test("root-workspace") {
    cleanWorkspace()
    writeLayout(
      s"""|/build.mill
          |//| mill-jvm-version: system
          |//| mill-version: 1.1.0-RC3-53-ff7b4d
          |
          |import mill.*, scalalib.*, javalib.*
          |import mill.util.Jvm
          |
          |object `package`
          |extends SbtModule:
          |  outer =>
          |
          |  def publishVersion = "1.0.0"
          |  def scalaVersion = "3.7.4"
          |
          |  def scalacOptions =
          |    "-Wunused:all" ::
          |    "-Wvalue-discard" ::
          |    Nil
          |
          |/src/main/scala/main.scala
          |package example
          |object Success {
          |  def scalaVersion = Other.scalaVersion
          |}
          |/src/main/scala/other.scala
          |package example
          |object Other {
          |  def scalaVersion: String = ""
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
      _ <- server.didSave("src/main/scala/main.scala")
      _ <- server.assertHover(
        "src/main/scala/main.scala",
        """|package example
           |object Success {
           |  def scalaVers@@ion = Other.scalaVersion
           |}
           |""".stripMargin,
        """|```scala
           |def scalaVersion: String
           |```
           |""".stripMargin,
      )
    } yield ()
  }

  test("java-1.0.0") {
    cleanWorkspace()

    val fileContent =
      """|
         |package foo;
         |
         |import net.sourceforge.argparse4j.ArgumentParsers;
         |import net.sourceforge.argparse4j.inf.Argume@@ntParser;
         |import net.sourceforge.argparse4j.inf.Namespace;
         |import org.thymeleaf.TemplateEngine;
         |import org.thymeleaf.context.Context;
         |
         |public class Foo {
         |  public static String generateHtml(String text) {
         |    Context context = new Context();
         |    context.setVariable("text", text);
         |    return new TemplateEngine().process("<h1 th:text=\"${text}\"></h1>", context);
         |  }
         |
         |  public static void main(String[] args) {
         |    ArgumentParser parser = ArgumentParsers.newFor("template")
         |        .build()
         |        .defaultHelp(true)
         |        .description("Inserts text into a HTML template");
         |
         |    parser.addArgument("-t", "--text").required(true).help("text to insert");
         |
         |    Namespace ns = null;
         |    try {
         |      ns = parser.parseArgs(args);
         |    } catch (Exception e) {
         |      System.out.println(e.getMessage());
         |      System.exit(1);
         |    }
         |
         |    System.out.println(generateHtml(ns.getString("text")));
         |  }
         |}
         |""".stripMargin

    for {
      _ <- initialize(
        s"""|
            |/build.mill
            |//| mill-version: ${V.millVersion}
            |//| mill-jvm-version: system
            |//// SNIPPET:BUILD
            |package build
            |import mill.*, javalib.*
            |
            |object foo extends JavaModule {
            |  def mvnDeps = Seq(
            |    mvn"net.sourceforge.argparse4j:argparse4j:0.9.0",
            |    mvn"org.thymeleaf:thymeleaf:3.1.1.RELEASE"
            |  )
            |
            |  object test extends JavaTests, TestModule.Junit4 {
            |    def mvnDeps = Seq(
            |      mvn"com.google.guava:guava:33.3.0-jre"
            |    )
            |  }
            |}
            |
            |/foo/src/foo/Foo.java
            |${fileContent.replace("@@", "")}
            |""".stripMargin
      )
      _ <- server.didOpen("foo/src/foo/Foo.java")
      _ <- server.didSave("foo/src/foo/Foo.java")
      definition <- server.definition(
        "foo/src/foo/Foo.java",
        fileContent,
        workspace,
      )
      _ = assert(
        definition
          .map(_.getUri())
          .mkString("\n")
          .contains("net/sourceforge/argparse4j/inf/ArgumentParser.java")
      )
    } yield ()
  }

}
