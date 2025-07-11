package tests.gradle

import scala.concurrent.Future

import scala.meta.internal.builds.GradleBuildTool
import scala.meta.internal.builds.GradleDigest
import scala.meta.internal.builds.GradleExperimentalBuildTool
import scala.meta.internal.metals.ClientCommands
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.Messages._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.DebugSessionParamsDataKind
import ch.epfl.scala.bsp4j.ScalaMainClass
import org.eclipse.lsp4j.MessageActionItem
import tests.BaseImportSuite

class GradleExperimentalLspSuite extends BaseImportSuite("gradle-import") {

  // GradleExperimentalBuildTool will be the main tool for this test,
  // which is what will be chosen when the user is prompted in the test
  def buildTool: GradleExperimentalBuildTool =
    GradleExperimentalBuildTool(() => userConfig, workspace)

  def alternativeBuildTool: GradleBuildTool =
    GradleBuildTool(() => userConfig, workspace)

  def chooseBuildToolMessage: String =
    ChooseBuildTool.params(List(buildTool, alternativeBuildTool)).getMessage

  def initializeMessageRequests: String =
    List(
      chooseBuildToolMessage,
      importBuildMessage,
    ).mkString("\n")

  def chooseGradleBuildTool: Seq[MessageActionItem] => MessageActionItem =
    actions =>
      actions
        .find(_.getTitle == "gradle (experimental)")
        .getOrElse(throw new Exception("build tool not found"))

  override def currentDigest(
      workspace: AbsolutePath
  ): Option[String] = GradleDigest.current(workspace)

  test("basic") {
    client.chooseBuildTool = chooseGradleBuildTool
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/build.gradle
            |plugins {
            |    id 'scala'
            |}
            |repositories {
            |    mavenCentral()
            |}
            |dependencies {
            |    implementation 'org.scala-lang:scala-library:${V.scala213}'
            |}
            |/src/main/scala/A.scala
            |
            |""".stripMargin
      )
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        initializeMessageRequests,
      )
      _ = client.messageRequests.clear() // restart
      _ = assertStatus(_.isInstalled)
      _ <- server.didChange("build.gradle")(_ + "\n// comment")
      _ = assertNoDiff(client.workspaceMessageRequests, "")
      _ <- server.didSave("build.gradle")
      // Comment changes do not trigger "re-import project" request
      _ = assertNoDiff(client.workspaceMessageRequests, "")
      _ <- server.didChange("build.gradle") { text =>
        text + "\ndef version = \"1.0.0\"\n"
      }
      _ = assertNoDiff(client.workspaceMessageRequests, "")
      _ = client.importBuildChanges = ImportBuildChanges.yes
      _ <- server.didSave("build.gradle")
    } yield {
      assertNoDiff(
        client.workspaceMessageRequests,
        importBuildChangesMessage,
      )
    }
  }

  test("i7031") {
    client.chooseBuildTool = chooseGradleBuildTool
    cleanWorkspace()
    for {
      _ <- initialize(
        Map(
          "root" -> s"""|/build.gradle
                        |plugins {
                        |    id 'scala'
                        |}
                        |repositories {
                        |    mavenCentral()
                        |}
                        |dependencies {
                        |    implementation 'org.scala-lang:scala-library:${V.scala213}'
                        |}
                        |""".stripMargin
        ),
        expectError = false,
      )
      _ <- server.fullServer
        .executeCommand(ServerCommands.ImportBuild.toExecuteCommandParams())
        .asScala
    } yield {
      assert(server.fullServer.folderServices.nonEmpty)
      assert(
        server.headServer.tables.buildTool
          .selectedBuildTool()
          .exists(_ == GradleExperimentalBuildTool.name)
      )
    }
  }

  test("inner") {
    client.chooseBuildTool = chooseGradleBuildTool
    client.importBuild = ImportBuild.yes
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/inner/build.gradle
            |plugins {
            |    id 'scala'
            |}
            |repositories {
            |    mavenCentral()
            |}
            |dependencies {
            |    implementation 'org.scala-lang:scala-library:${V.scala213}'
            |}
            |/inner/src/main/scala/A.scala
            |object A {
            |  val foo: Int = "aaa"
            |}
            |""".stripMargin
      )
      _ <- server.server.indexingPromise.future
      _ = assert(server.server.bspSession.get.main.isBloop)
      buildTool <- server.headServer.buildToolProvider.supportedBuildTool()
      _ = assertEquals(
        buildTool.get.buildTool.executableName,
        "gradle (experimental)",
      )
      _ = assertEquals(
        buildTool.get.buildTool.projectRoot,
        workspace.resolve("inner"),
      )
      _ <- server.didOpen("inner/src/main/scala/A.scala")
      _ <- server.didSave("inner/src/main/scala/A.scala")
      _ = assertNoDiff(
        client.pathDiagnostics("inner/src/main/scala/A.scala"),
        """|inner/src/main/scala/A.scala:2:18: error: type mismatch;
           | found   : String("aaa")
           | required: Int
           |  val foo: Int = "aaa"
           |                 ^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  val javaOnlyTestName = "java-only-run"
  test(javaOnlyTestName) {
    client.chooseBuildTool = chooseGradleBuildTool
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/build.gradle
            |plugins {
            |    id 'java'
            |}
            |repositories {
            |    mavenCentral()
            |}
            |/src/main/java/a/Main.java
            |package a;
            |
            |public class Main {
            |    public static void main(String[] args) {
            |        System.out.println("Hello world!");
            |        System.exit(0);
            |    }
            |}
            |/src/main/scala/A.scala
            |
            |""".stripMargin
      )
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        initializeMessageRequests,
      )
      _ = client.messageRequests.clear()
      _ = assertStatus(_.isInstalled)
      _ <- server.didSave("src/main/java/a/Main.java")
      debugger <- server.startDebugging(
        s"${javaOnlyTestName}-main",
        DebugSessionParamsDataKind.SCALA_MAIN_CLASS,
        new ScalaMainClass(
          "a.Main",
          Nil.asJava,
          Nil.asJava,
        ),
      )
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assertNoDiff(output, "Hello world!")
  }

  test("transitive") {
    client.chooseBuildTool = chooseGradleBuildTool
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/build.gradle
            |plugins {
            |    id 'scala'
            |}
            |repositories {
            |    mavenCentral()
            |}
            |dependencies {
            |    implementation 'org.scala-lang:scala-reflect:${V.scala213}'
            |}
            |/src/main/scala/A.scala
            |
            |""".stripMargin
      )
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        initializeMessageRequests,
      )
      _ = client.messageRequests.clear()
      _ = assertStatus(_.isInstalled)
    } yield assertNoDiff(client.workspaceMessageRequests, "")
  }

  test("force-command") {
    client.chooseBuildTool = chooseGradleBuildTool
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/build.gradle
            |plugins {
            |    id 'scala'
            |}
            |repositories {
            |    mavenCentral()
            |}
            |dependencies {
            |    implementation 'org.scala-lang:scala-library:${V.scala213}'
            |}
            |/src/main/scala/A.scala
            |
            |""".stripMargin
      )
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        initializeMessageRequests,
      )
      _ <- server.server.buildServerPromise.future
      _ = client.progressParams.clear() // restart
      _ <- server.executeCommand(ServerCommands.ImportBuild)
      _ = assertNoDiff(
        client.beginProgressMessages,
        List(
          progressMessage,
          Messages.importingBuild,
          Messages.indexing,
        ).mkString("\n"),
      )
    } yield ()
  }

  test("new-dependency") {
    client.chooseBuildTool = chooseGradleBuildTool
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/build.gradle
            |plugins {
            |    id 'scala'
            |}
            |repositories {
            |    mavenCentral()
            |}
            |dependencies {
            |    implementation 'org.scala-lang:scala-library:${V.scala213}'
            |}
            |/src/main/scala/reload/Main.scala
            |package reload
            |object Main extends App {
            |  println("sourcecode.Line(42)")
            |}
            |""".stripMargin
      )
      _ <- server.didOpen("src/main/scala/reload/Main.scala")
      _ = assertNoDiff(client.workspaceDiagnostics, "")
      _ = client.importBuildChanges = ImportBuildChanges.yes
      _ <- server.didChange("build.gradle") { text =>
        s"""$text
           |dependencies {
           |    implementation 'com.lihaoyi:sourcecode_2.12:0.1.4'
           |}
           |""".stripMargin
      }
      _ <- server.didSave("build.gradle")
      _ <-
        server
          .didChange("src/main/scala/reload/Main.scala") { text =>
            text.replaceAll("\"", "")
          }
          .recover { case e => scribe.error("compile", e) }
      _ <- server.didSave("src/main/scala/reload/Main.scala")
      _ = assertNoDiff(client.workspaceDiagnostics, "")
    } yield ()
  }

  test("error") {
    client.chooseBuildTool = chooseGradleBuildTool
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/build.gradle
           |, syntax error
           |/src/main/scala/A.scala
           |
           |""".stripMargin,
        expectError = true,
      )
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        initializeMessageRequests,
      )
      _ = assertNoDiff(
        client.workspaceShowMessages,
        ImportProjectFailed.getMessage,
      )
      _ = assertStatus(!_.isInstalled)
      _ = client.messageRequests.clear()
      _ <- server.didChange("build.gradle") { _ =>
        s"""|plugins {
            |    id 'scala'
            |}
            |repositories {
            |    mavenCentral()
            |}
            |dependencies {
            |    implementation 'org.scala-lang:scala-library:${V.scala213}'
            |}
            |""".stripMargin
      }
      _ <- server.didSave("build.gradle")
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        importBuildMessage,
      )
      _ = assertStatus(_.isInstalled)
    } yield ()
  }

  private def projectWithVersion(version: String) = {
    s"""|plugins {
        |    id 'scala'
        |}
        |repositories {
        |    mavenCentral()
        |}
        |dependencies {
        |    implementation 'org.scala-lang:scala-library:$version'
        |}
        |""".stripMargin
  }

  test("different-scala".flaky) {
    client.chooseBuildTool = chooseGradleBuildTool
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/build.gradle
           |${projectWithVersion("2.12.7")}
           |/a/build.gradle
           |${projectWithVersion("2.12.4")}
           |/b/build.gradle
           |${projectWithVersion("2.12.3")}
           |/c/build.gradle
           |${projectWithVersion("2.11.12")}
           |/d/build.gradle
           |${projectWithVersion("2.11.8")}
           |/e/build.gradle
           |${projectWithVersion("2.10.7")}
           |/f/build.gradle
           |${projectWithVersion(V.scala212)}
           |/g/build.gradle
           |${projectWithVersion(V.scala213)}
           |/settings.gradle
           |include 'a'
           |include 'b'
           |include 'c'
           |include 'd'
           |include 'e'
           |include 'f'
           |include 'g'
           |/a/src/main/scala/a/A.scala
           |package a
           |object A // 2.12.4
           |/b/src/main/scala/a/A.scala
           |package a // 2.12.3
           |object A
           |/c/src/main/scala/a/A.scala
           |package a
           |object A // 2.11.12
           |/d/src/main/scala/a/A.scala
           |package a
           |object A // 2.11.8
           |/e/src/main/scala/a/A.scala
           |package a
           |object A // 2.10.7
           |/f/src/main/scala/a/A.scala
           |package a
           |object A // ${V.scala212}
           |/g/src/main/scala/a/A.scala
           |package a
           |object A // ${V.scala213}
           |""".stripMargin,
        expectError = true,
      )
      _ = assertStatus(_.isInstalled)
      _ = assertNoDiff(
        client.messageRequests.peekLast(),
        // only main projects since no empty test targets are created for gradle
        CheckDoctor.multipleMisconfiguredProjects(5),
      )
      _ <- Future.sequence(
        ('a' to 'f')
          .map(project => s"$project/src/main/scala/a/A.scala")
          .map(file => server.didOpen(file))
      )
      _ = assertNoDiff(client.workspaceDiagnostics, "")
      _ = {
        val expected =
          ClientCommands.ReloadDoctor.id :: ClientCommands.RunDoctor.id :: Nil
        val actual = client.workspaceClientCommands
        assert(actual.startsWith(expected))
      }
    } yield ()
  }

  test("fatal-warnings", maxRetry = 3) {
    client.chooseBuildTool = chooseGradleBuildTool
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/build.gradle
           |plugins {
           |    id 'scala'
           |}
           |repositories {
           |    mavenCentral()
           |}
           |dependencies {
           |    implementation 'org.scala-lang:scala-library:${V.scala213}'
           |}
           |tasks.withType(ScalaCompile) {
           |    scalaCompileOptions.additionalParameters = [
           |         '-Xfatal-warnings',
           |         '-Ywarn-unused'
           |    ]
           |}
           |/src/main/scala/warning/Warning.scala
           |import scala.concurrent.Future // unused
           |object Warning
           |object A{
           |  object B
           |}
           |""".stripMargin
      )
      _ = assertStatus(_.isInstalled)
      _ <- server.didOpen("src/main/scala/warning/Warning.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """
          |src/main/scala/warning/Warning.scala:1:25: error: Unused import
          |import scala.concurrent.Future // unused
          |                        ^^^^^^
        """.stripMargin,
      )
      // we should still have references despite fatal warning
      refs <- server.workspaceReferences()
      _ = assertNoDiff(
        refs.references.map(_.symbol).sorted.mkString("\n"),
        """|_empty_/A.
           |_empty_/A.B.
           |_empty_/Warning.
           |""".stripMargin,
      )
    } yield ()
  }

  test("properly-removes-old-config") {
    client.chooseBuildTool = chooseGradleBuildTool
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/inner/build.gradle
            |plugins {
            |    id 'scala'
            |}
            |repositories {
            |    mavenCentral()
            |}
            |dependencies {
            |    implementation 'org.scala-lang:scala-library:${V.scala213}'
            |}
            |/inner/settings.gradle
            |rootProject.name = 'some-project-name'
            |/inner/src/main/scala/A.scala
            |object A {
            |  val foo: Int = "aaa"
            |}
            |""".stripMargin
      )
      _ = assertNoDiff(
        server.server.buildTargets.all
          .map(_.getDisplayName())
          .toSeq
          .sorted
          .mkString("\n"),
        Seq(
          "some-project-name-main",
          "some-project-name-test",
        ).mkString("\n"),
      )
      _ <- server.didChange("inner/settings.gradle")(_ =>
        "rootProject.name = 'new-name'\n"
      )
      _ = client.importBuildChanges = ImportBuildChanges.yes
      _ <- server.didSave("inner/settings.gradle")
      _ = assertNoDiff(
        server.server.buildTargets.all
          .map(_.getDisplayName())
          .toSeq
          .sorted
          .mkString("\n"),
        Seq(
          "new-name-main",
          "new-name-test",
        ).mkString("\n"),
      )
    } yield ()
  }

}
