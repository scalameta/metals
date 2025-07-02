package tests.sbt

import scala.concurrent.Future
import scala.concurrent.Promise

import scala.meta.internal.builds.SbtBuildTool
import scala.meta.internal.builds.SbtDigest
import scala.meta.internal.metals.CreateSession
import scala.meta.internal.metals.Disconnect
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.Messages.ImportBuildChanges
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.internal.process.SystemProcess
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.DebugSessionParamsDataKind
import ch.epfl.scala.bsp4j.ScalaMainClass
import org.eclipse.lsp4j.MessageActionItem
import tests.BaseImportSuite
import tests.JavaHomeChangeTest
import tests.SbtBuildLayout
import tests.SbtServerInitializer
import tests.ScriptsAssertions
import tests.TestSemanticTokens

/**
 * Basic suite to ensure that a connection to sbt server can be made.
 */
class SbtServerSuite
    extends BaseImportSuite("sbt-server", SbtServerInitializer)
    with ScriptsAssertions
    with JavaHomeChangeTest {

  val preBspVersion = "1.3.13"
  val supportedMetaBuildVersion = "1.8.0"
  val supportedBspVersion = V.sbtVersion
  val scalaVersion = V.scala213
  val buildTool: SbtBuildTool = SbtBuildTool(None, workspace, () => userConfig)
  var compilationCount = 0

  override def beforeEach(context: BeforeEach): Unit = {
    compilationCount = 0
    onStartCompilation = { () => compilationCount += 1 }
    super.beforeEach(context)
  }

  override def currentDigest(
      workspace: AbsolutePath
  ): Option[String] = SbtDigest.current(workspace)

  test("switch to sbt when bloop import build request ignored") {
    cleanWorkspace()
    val hangPromise = Promise[MessageActionItem]()
    val gotImportBuildMessage = Promise[Unit]()
    client.futureShowMessageRequestHandler = { message =>
      if (message.getMessage == importBuildMessage) {
        gotImportBuildMessage.trySuccess(())
        Some(hangPromise.future)
      } else None
    }

    writeLayout(
      s"""|/project/build.properties
          |sbt.version=${V.sbtVersion}
          |/build.sbt
          |${SbtBuildLayout.commonSbtSettings}
          |ThisBuild / scalaVersion := "${V.scala213}"
          |val a = project.in(file("a"))
          |val b = project.in(file("b"))
          |/a/src/main/scala/A.scala
          |
          |object A {
          |  val foo: Int = "aaa"
          |}
          |""".stripMargin
    )

    for {
      _ <- server.initialize()
      _ = server.initialized()
      _ <- gotImportBuildMessage.future
      _ = client.selectBspServer = { items =>
        items.find(_.getTitle().contains("sbt")).getOrElse {
          throw new RuntimeException(
            "sbt was expected in the test, but not found"
          )
        }
      }
      _ <- server.executeCommand(ServerCommands.BspSwitch).ignoreValue
      _ <- server.headServer.buildServerPromise.future
      _ = assert(server.headServer.connectionProvider.bspSession.get.main.isSbt)
    } yield hangPromise.success(Messages.ImportBuild.notNow)
  }

  test("too-old") {
    cleanWorkspace()
    writeLayout(
      s"""|/project/build.properties
          |sbt.version=$preBspVersion
          |/build.sbt
          |${SbtBuildLayout.commonSbtSettings}
          |scalaVersion := "${V.scala213}"
          |""".stripMargin
    )
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
      // Attempt to create a .bsp/sbt.json file
      _ <- server.executeCommand(ServerCommands.GenerateBspConfig)
    } yield {
      assertNoDiff(
        client.workspaceShowMessages,
        Messages.NoBspSupport.toString,
      )
    }
  }

  test("generate") {
    def sbtBspConfig = workspace.resolve(".bsp/sbt.json")
    def isBspConfigValid =
      sbtBspConfig.readText.contains("sbt")
    cleanWorkspace()
    writeLayout(SbtBuildLayout("", V.scala213))
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
      _ = assert(!sbtBspConfig.exists)
      // At this point, we want to use sbt server, so create the sbt.json file.
      _ <- server.executeCommand(ServerCommands.GenerateBspConfig)
    } yield {
      assert(isBspConfigValid)
      assert(sbtBspConfig.exists)
    }
  }

  test("inner-project") {
    cleanWorkspace()
    client.importBuildChanges = ImportBuildChanges.yes
    for {
      _ <- initialize(
        SbtBuildLayout(
          """|/inner/a/src/main/scala/A.scala
             |
             |object A {
             |  val foo: Int = "aaa"
             |}
             |/.metals/a.txt
             |
             |""".stripMargin,
          V.scala213,
          "/inner",
        )
      )
      _ <- server.server.indexingPromise.future
      _ = assert(workspace.resolve(".bsp/sbt.json").exists)
      _ = assert(server.server.bspSession.get.main.isSbt)
      _ <- server.didOpen("inner/a/src/main/scala/A.scala")
      _ <- server.didSave("inner/a/src/main/scala/A.scala")
      _ = assertNoDiff(
        client.pathDiagnostics("inner/a/src/main/scala/A.scala"),
        """|inner/a/src/main/scala/A.scala:3:18: error: type mismatch;
           | found   : String("aaa")
           | required: Int
           |  val foo: Int = "aaa"
           |                 ^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("reload plugins") {
    // should reload existing server after writing the metals.sbt plugin file
    cleanWorkspace()
    val layout = SbtBuildLayout("", V.scala213)
    writeLayout(layout)
    assert(workspace.exists)
    def startSbtServer(): Future[Int] = {
      val sbtProcess = SystemProcess.run(
        List("sbt", "-client", "exit"),
        workspace,
        true,
        Map.empty,
      )
      sbtProcess.complete
    }
    for {
      code <- startSbtServer()
      _ = assert(code == 0)
      _ = assert(workspace.resolve(".bsp/sbt.json").exists)
      _ <- initializer.initialize(workspace, server, client, false, userConfig)
      _ <- server.initialized()
    } yield {
      // should not contain the 'Navigation will not work for this build due to mis-configuration.' message
      assertNoDiff(
        client.workspaceMessageRequests,
        List(
          importBuildMessage,
          Messages.BspSwitch.message,
        ).mkString("\n"),
      )
    }
  }

  test("sbt-2.0.0") {
    cleanWorkspace()
    client.importBuildChanges = ImportBuildChanges.yes
    for {
      _ <- initialize(
        s"""|/project/build.properties
            |sbt.version=2.0.0-M4
            |/build.sbt
            |
            |scalaVersion := "$scalaVersion"
            |val a = project.in(file("a"))
            |val b = project.in(file("b"))
            |/a/src/main/scala/A.scala
            |
            |object A{
            |  val foo = 1
            |  foo + foo
            |}
            |""".stripMargin
      )
      _ <- server.server.indexingPromise.future
      references <- server.references("a/src/main/scala/A.scala", "foo")
      _ = assertEmpty(client.workspaceDiagnostics)
      _ = assertNoDiff(
        references,
        """|a/src/main/scala/A.scala:3:7: info: reference
           |  val foo = 1
           |      ^^^
           |a/src/main/scala/A.scala:4:3: info: reference
           |  foo + foo
           |  ^^^
           |a/src/main/scala/A.scala:4:9: info: reference
           |  foo + foo
           |        ^^^
           |""".stripMargin,
      )
      _ = assertEmpty(client.workspaceShowMessages)
    } yield ()

  }

  test("reload") {
    cleanWorkspace()
    client.importBuildChanges = ImportBuildChanges.yes
    for {
      _ <- initialize(
        SbtBuildLayout(
          """|/a/src/main/scala/A.scala
             |
             |object A{
             |  val foo = 1
             |  foo + foo
             |}
             |""".stripMargin,
          V.scala213,
        )
      )
      // reload build after build.sbt changes
      _ <- server.executeCommand(ServerCommands.ResetNotifications)
      _ <- server.didChange("build.sbt") { text =>
        s"""$text
           |ibraryDependencies += "com.lihaoyi" %% "sourcecode" % "0.3.0"
           |""".stripMargin
      }
      _ <- server.didSave("build.sbt")
      _ = {
        assertNoDiff(
          client.workspaceErrorShowMessages,
          Messages.ReloadProjectFailed.getMessage,
        )
        client.showMessages.clear()
      }
      // This is a little hacky but up above this promise is succeeded already, so down
      // below it won't wait until it reconnects to Sbt and indexed like we want
      _ = server.server.indexingPromise = Promise()
      _ <- server.didChange("build.sbt") { text =>
        text.replace("ibraryDependencies", "libraryDependencies")
      }
      _ <- server.didSave("build.sbt")
      _ = {
        assert(client.workspaceErrorShowMessages.isEmpty)
      }
      _ <- server.server.indexingPromise.future
      references <- server.references("a/src/main/scala/A.scala", "foo")
      _ = assertEmpty(client.workspaceDiagnostics)
      _ = assertNoDiff(
        references,
        """|a/src/main/scala/A.scala:3:7: info: reference
           |  val foo = 1
           |      ^^^
           |a/src/main/scala/A.scala:4:3: info: reference
           |  foo + foo
           |  ^^^
           |a/src/main/scala/A.scala:4:9: info: reference
           |  foo + foo
           |        ^^^
           |""".stripMargin,
      )
      _ <- server.didChange("build.sbt") { text =>
        text.replace(
          "val a = project.in(file(\"a\"))",
          """|val a = project.in(file("a")).settings(
             |  libraryDependencies += "org.scalameta" %% "scalameta" % "4.6.0"
             |)
             |""".stripMargin,
        )
      }
      _ <- server.didSave("build.sbt")
      _ = {
        assert(client.workspaceErrorShowMessages.isEmpty)
      }
      _ <- server.didChange("a/src/main/scala/A.scala") { _ =>
        """|object A{
           |  val a: scala.meta.Defn.Class = ???
           |}
           |""".stripMargin
      }
      _ <- server.didSave("a/src/main/scala/A.scala")
      _ <- server.assertHoverAtLine(
        "a/src/main/scala/A.scala",
        "  val a: scala.meta.Defn.C@@lass = ???",
        """|```scala
           |abstract trait Class: Defn.Class
           |```
           |""".stripMargin,
      )
      _ = assertNoDiff(
        references,
        """|a/src/main/scala/A.scala:3:7: info: reference
           |  val foo = 1
           |      ^^^
           |a/src/main/scala/A.scala:4:3: info: reference
           |  foo + foo
           |  ^^^
           |a/src/main/scala/A.scala:4:9: info: reference
           |  foo + foo
           |        ^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("restart-server") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/project/build.properties
            |sbt.version=${V.sbtVersion}
            |/build.sbt
            |${SbtBuildLayout.commonSbtSettings}
            |scalaVersion := "${V.scala213}"
            |""".stripMargin
      )
      previousSession = server.headServer.connectionProvider.bspSession.get
      _ = assert(previousSession.main.isSbt)
      _ <- server.headServer.connect(CreateSession(shutdownBuildServer = true))
      newSession = server.headServer.connectionProvider.bspSession.get
      _ = assert(newSession.main.isSbt)
      _ = assert(
        server.headServer.connectionProvider.bspSession.get != previousSession,
        "New sbt session was not created after restart",
      )
    } yield ()
  }

  test("debug") {
    cleanWorkspace()
    val mainClass = new ScalaMainClass(
      "a.Main",
      List("Bar").asJava,
      List("-Dproperty=Foo").asJava,
    )
    mainClass.setEnvironmentVariables(List("HELLO=Foo").asJava)
    for {
      _ <- initialize(
        s"""|/project/build.properties
            |sbt.version=${V.sbtVersion}
            |/build.sbt
            |import sbt.internal.bsp.BuildTargetIdentifier
            |import java.net.URI
            |${SbtBuildLayout.commonSbtSettings}
            |scalaVersion := "${V.scala213}"
            |Compile / bspTargetIdentifier := {
            |  BuildTargetIdentifier(new URI("debug"))
            |}
            |/src/main/scala/a/Main.scala
            |package a
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
      debugger <- server.startDebugging(
        "debug",
        DebugSessionParamsDataKind.SCALA_MAIN_CLASS,
        mainClass,
      )
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assertNoDiff(output, "FooBarFoo")
  }

  // this test fails if semantidb is not enabled
  // or not correctly configured in the meta-build target.
  test("meta-build-target") {
    cleanWorkspace()
    val layout =
      s"""|/project/build.properties
          |sbt.version=$supportedMetaBuildVersion
          |/build.sbt
          |${SbtBuildLayout.commonSbtSettings}
          |""".stripMargin
    for {
      _ <- initialize(layout)
    } yield {
      // assert no misconfiguration message
      assertNoDiff(
        client.workspaceMessageRequests,
        List(
          importBuildMessage,
          Messages.BspSwitch.message,
        ).mkString("\n"),
      )
      // assert contains the meta-build-target-build
      assertNoDiff(
        server.server.buildTargets.all
          .map(_.getDisplayName())
          .toSeq
          .sorted
          .mkString("\n"),
        Seq(
          "meta-build-target",
          "meta-build-target-build",
          "meta-build-target-test",
        ).mkString("\n"),
      )
    }
  }

  test("twirl-hover") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/project/build.properties
            |sbt.version=${V.sbtVersion}
            |/src/main/twirl/example.scala.html
            |@(name: String)
            |<h1>Hello @name!</h1>
            |/project/plugins.sbt
            |addSbtPlugin("org.playframework.twirl" % "sbt-twirl" % "2.0.8")
            |/build.sbt
            |enablePlugins(SbtTwirl)
            |Compile / unmanagedSourceDirectories := Seq(
            |  (baseDirectory.value / "src" / "main" / "scala"),
            |  (baseDirectory.value / "src" / "main" / "scala-3"),
            |  (baseDirectory.value / "src" / "main" / "java"),
            |  (baseDirectory.value / "src" / "main" / "twirl")
            |)
            |scalaVersion := "${V.scala213}"
            |""".stripMargin
      )
      _ <- server.didOpen("src/main/twirl/example.scala.html")
      _ <- server.didOpen("build.sbt")
      _ <- server.assertHover(
        "src/main/twirl/example.scala.html",
        """|@\@@(name: String)
           |<h1>Hello @name!</h1>
           |""".stripMargin,
        """|```scala
           |val name: String
           |```""".stripMargin,
      )
    } yield ()
  }
  test("infinite-loop") {
    cleanWorkspace()
    val layout =
      s"""|/project/build.properties
          |sbt.version=$supportedMetaBuildVersion
          |/build.sbt
          |${SbtBuildLayout.commonSbtSettings}
          |Compile / sourceGenerators += Def.task {
          |  val content = "object Foo\\n"
          |  val file = target.value / "Foo.scala"
          |  IO.write(file, content)
          |  Seq(file)
          |}
          |""".stripMargin
    for {
      _ <- initialize(layout)
      // make sure to compile once
      _ <- server.server.compilations.compileFile(
        workspace.resolve("target/Foo.scala")
      )
    } yield {
      // Sleep 100 ms: that should be enough to see the compilation looping
      Thread.sleep(100)
      // Check that the compilation is not looping
      assertEquals(compilationCount, 1)
    }
  }

  test("semantic-highlight") {
    cleanWorkspace()
    val expected =
      s"""|<<lazy>>/*modifier*/ <<val>>/*keyword*/ <<root>>/*variable,definition,readonly*/ = (<<project>>/*class*/ <<in>>/*method*/ <<file>>/*method*/(<<".">>/*string*/))
          |  .<<configs>>/*method*/(<<IntegrationTest>>/*variable,readonly,deprecated*/)
          |  .<<settings>>/*method*/(
          |    <<Defaults>>/*class*/.<<itSettings>>/*variable,readonly,deprecated*/,
          |    <<inThisBuild>>/*method*/(
          |      <<List>>/*class*/(
          |        <<organization>>/*variable,readonly*/ <<:=>>/*method*/ <<"com.example">>/*string*/,
          |        <<scalaVersion>>/*variable,readonly*/ <<:=>>/*method*/ <<"2.13.10">>/*string*/,
          |        <<scalacOptions>>/*variable,readonly*/ <<:=>>/*method*/ <<List>>/*class*/(<<"-Xsource:3">>/*string*/, <<"-Xlint:adapted-args">>/*string*/),
          |        <<javacOptions>>/*variable,readonly*/ <<:=>>/*method*/ <<List>>/*class*/(
          |          <<"-Xlint:all">>/*string*/,
          |          <<"-Xdoclint:accessibility,html,syntax">>/*string*/
          |        )
          |      )
          |    ),
          |    <<name>>/*variable,readonly*/ <<:=>>/*method*/ <<"bsp-tests-source-sets">>/*string*/
          |  )
          |
          |<<resolvers>>/*variable,readonly*/ <<++=>>/*method*/ <<Resolver>>/*class*/.<<sonatypeOssRepos>>/*method*/(<<"snapshot">>/*string*/)
          |<<libraryDependencies>>/*variable,readonly*/ <<+=>>/*method*/ <<"org.scalatest">>/*string*/ <<%%>>/*method*/ <<"scalatest">>/*string*/ <<%>>/*method*/ <<"3.2.9">>/*string*/ <<%>>/*method*/ <<Test>>/*variable,readonly*/
          |<<libraryDependencies>>/*variable,readonly*/ <<+=>>/*method*/ <<"org.scalameta">>/*string*/ <<%%>>/*method*/ <<"scalameta">>/*string*/ <<%>>/*method*/ <<"4.6.0">>/*string*/
          |
           """.stripMargin

    val fileContent =
      TestSemanticTokens.removeSemanticHighlightDecorations(expected)
    for {
      _ <- initialize(
        s"""|/build.sbt
            |$fileContent
           """.stripMargin
      )
      _ <- server.didChangeConfiguration(
        """{
          |  "enable-semantic-highlighting": true
          |}
          |""".stripMargin
      )
      _ <- server.didOpen("build.sbt")
      _ <- server.didSave("build.sbt")
      _ <- server.assertSemanticHighlight(
        "build.sbt",
        expected,
        fileContent,
      )
    } yield ()
  }

  test("java-hover") {
    cleanWorkspace()
    for {
      _ <- initialize(
        SbtBuildLayout(
          """|/a/src/main/java/a/A.java
             |package a;
             |public class A{
             |  String name = "";
             |}
             |""".stripMargin,
          V.scala213,
        )
      )
      _ <- server.hover("a/src/main/java/A.java", "String na@@me", workspace)
      _ <- server.didOpen("build.sbt")
      _ <- server.didSave("build.sbt")
      _ <- server.assertHover(
        "a/src/main/java/a/Main.java",
        """"|package a;
           |public class A{
           |  String na@@me = "";
           |}
           |""".stripMargin,
        """|```java
           |java.lang.String name
           |```
           |""".stripMargin,
      )
    } yield ()
  }

  test("build-sbt") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/project/build.properties
            |sbt.version=${V.sbtVersion}
            |/build.sbt
            |${SbtBuildLayout.commonSbtSettings}
            |ThisBuild / scalaVersion := "${V.scala213}"
            |val a = project.in(file("a"))
            |/a/src/main/scala/a/A.scala
            |package a
            |object A {
            | val a = 1
            |}
            |""".stripMargin
      )
      _ <- server.didOpen("build.sbt")
      res <- definitionsAt(
        "build.sbt",
        s"ThisBuild / sc@@alaVersion := \"${V.scala213}\"",
      )
      _ = assert(res.length == 1)
      _ = assertNoDiff(res.head.getUri().toAbsolutePath.filename, "Keys.scala")
    } yield ()
  }

  checkJavaHomeUpdate(
    "sbt-java-home-update",
    fileContent => SbtBuildLayout(fileContent, V.scala213),
  )

  test("meta-build-references") {
    cleanWorkspace()

    val buildSbt =
      s"""|${SbtBuildLayout.commonSbtSettings}
          |ThisBuild / scalaVersion := "${V.scala213}"
          |val a = project.in(file(V.<<filename>>))
          |""".stripMargin
    val buildSbtBase = buildSbt.replaceAll("<<|>>", "")

    val v =
      s"""|object V {
          |  val <<filen@@ame>> = "a"
          |}
          |""".stripMargin
    val vBase = v.replaceAll("<<|>>|@@", "")

    for {
      _ <- initialize(
        s"""|/project/build.properties
            |sbt.version=${V.sbtVersion}
            |/project/V.scala
            |$vBase
            |/build.sbt
            |$buildSbtBase
            |""".stripMargin
      )
      _ <- server.server.indexingPromise.future
      _ <- server.didOpen("project/V.scala")
      _ <-
        server.assertReferences(
          "project/V.scala",
          v.replaceAll("<<|>>", ""),
          Map(
            "project/V.scala" -> v.replaceAll("@@", ""),
            "build.sbt" -> buildSbt,
          ),
          Map(
            "project/V.scala" -> vBase,
            "build.sbt" -> buildSbtBase,
          ),
        )

    } yield ()
  }

  test("no-fallback-to-bloop") {
    cleanWorkspace()
    val sbtBspConfig = workspace.resolve(".bsp/sbt.json")

    for {
      _ <- initialize(
        s"""|/project/build.properties
            |sbt.version=${V.sbtVersion}
            |/build.sbt
            |${SbtBuildLayout.commonSbtSettings}
            |scalaVersion := "${V.scala213}"
            |val a = project.in(file("a"))
            |/a/src/main/scala/a/A.scala
            |package a
            |object A {
            | val a = 1
            |}
            |""".stripMargin
      )
      _ = assert(
        sbtBspConfig.exists,
        "sbt.json should exist after initialization",
      )
      _ = assert(
        server.server.bspSession.get.main.isSbt,
        "Should be connected to sbt",
      )
      _ <- server.headServer.connect(Disconnect(shutdownBuildServer = true))
      _ = assert(server.server.bspSession.isEmpty, "Should be disconnected")
      _ = {
        assert(sbtBspConfig.exists, "sbt.json should exist before removal")
        sbtBspConfig.delete()
        assert(!sbtBspConfig.exists, "sbt.json should be deleted")
      }
      _ <- server.headServer.connect(CreateSession(shutdownBuildServer = false))
      _ = server.server.bspSession match {
        case Some(session) if session.main.isSbt =>
        case otherwise =>
          fail(s"Should be connected to sbt, but got $otherwise")
      }

    } yield ()
  }

}
