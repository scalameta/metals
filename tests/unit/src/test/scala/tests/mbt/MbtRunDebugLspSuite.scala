package tests.mbt

import java.net.Socket
import java.net.URI

import scala.concurrent.Future
import scala.util.Properties

import scala.meta.internal.metals.DebugDiscoveryParams
import scala.meta.internal.metals.DebugSession
import scala.meta.internal.metals.InitializationOptions
import scala.meta.internal.metals.JsonParser.*
import scala.meta.internal.metals.MetalsEnrichments.*
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.config.AutoImportBuildKind
import scala.meta.internal.metals.config.ReferenceProviderConfig
import scala.meta.internal.metals.config.TestUserInterfaceKind
import scala.meta.internal.metals.config.UserConfiguration
import scala.meta.internal.metals.config.WorkspaceSymbolProviderConfig
import scala.meta.internal.metals.mbt.MbtBuildServer

import ch.epfl.scala.bsp4j.DebugSessionParams
import ch.epfl.scala.bsp4j.DebugSessionParamsDataKind
import ch.epfl.scala.bsp4j.ScalaMainClass
import tests.BaseCodeLensLspSuite
import tests.BuildInfo
import tests.MbtJsonBuilder

class MbtRunDebugLspSuite extends BaseCodeLensLspSuite("mbt-run-debug") {

  override def serverConfig: MetalsServerConfig =
    super.serverConfig.copy(debugServerStartTimeout = 1)

  override protected def initializationOptions: Some[InitializationOptions] =
    Some(
      InitializationOptions.Default
        .copy(testExplorerProvider = Some(true), debuggingProvider = Some(true))
    )

  override def userConfig: UserConfiguration =
    super.userConfig.copy(
      buildOnChange = false,
      buildOnFocus = false,
      workspaceSymbolProvider = WorkspaceSymbolProviderConfig.MBT,
      referenceProvider = ReferenceProviderConfig.MBT,
      preferredBuildServer = Some(MbtBuildServer.name),
      autoImportBuilds = AutoImportBuildKind.All,
      testUserInterface = TestUserInterfaceKind.TestExplorer,
    )

  override def initializeGitRepo: Boolean = true

  for (isTest <- List(false, true)) {
    test(s"slow-preparation-debug-${if (isTest) "test" else "main"}") {
      assume(!Properties.isWin, "fake Maven wrapper requires a POSIX shell")
      cleanWorkspace()
      val mbtJson = new MbtJsonBuilder(BuildInfo.scalaVersion)
        .addScalaLibrary()
        .addJavaDependency("junit", "junit", "4.13.2")
        .addNamespace("app", List("src/**"))
        .build()

      for {
        _ <- initialize(
          s"""|/.metals/mbt.json
              |$mbtJson
              |/pom.xml
              |<project>
              |  <modelVersion>4.0.0</modelVersion>
              |  <groupId>example</groupId>
              |  <artifactId>app</artifactId>
              |  <version>1.0</version>
              |</project>
              |/mvnw
              |#!/bin/sh
              |for argument in "$$@"; do
              |  case "$$argument" in
              |    -DmbtOutputFile=*) cp .metals/mbt.json "$${argument#*=}"; exit 0 ;;
              |  esac
              |done
              |case " $$* " in
              |  *" install "*) printf 'compile\\n' >> compile-count ;;
              |esac
              |sleep 2
              |/src/Main.java
              |package example;
              |public class Main {
              |  public static void main(String[] args) {}
              |  @org.junit.Test
              |  public void sampleTest() {}
              |}
              |""".stripMargin,
          runAdditionalCommands =
            root => assert(root.resolve("mvnw").toFile.setExecutable(true)),
        )
        _ = assertConnectedToBuildServer("MBT")
        params <-
          if (isTest) {
            for {
              _ <- server.didOpen("src/Main.java")
              discovered <- server.executeDiscoverMainClassesCommand(
                DebugDiscoveryParams(
                  workspace.resolve("src/Main.java").toURI.toString,
                  "testFile",
                )
              )
              _ = assertEquals(discovered.getDataKind, "scala-test-suites")
              _ = assert(discovered.getData.toString.contains("example.Main"))
            } yield discovered
          } else {
            val target = server.server.buildTargets.allBuildTargetIds.head
            val params = new DebugSessionParams(List(target).asJava)
            params.setDataKind(DebugSessionParamsDataKind.SCALA_MAIN_CLASS)
            params.setData(
              new ScalaMainClass(
                "example.Main",
                Nil.asJava,
                Nil.asJava,
              ).toJson
            )
            Future.successful(params)
          }
        _ = assert(!workspace.resolve("compile-count").exists)
        session <- server.executeCommandUnsafe(
          ServerCommands.StartDebugAdapter.id,
          Seq(params),
        )
      } yield {
        assert(session.isInstanceOf[DebugSession])
        assertEquals(workspace.resolve("compile-count").readText, "compile\n")
        val uri = URI.create(session.asInstanceOf[DebugSession].uri)
        val socket = new Socket(uri.getHost, uri.getPort)
        socket.close()
      }
    }
  }

  test("discover-run-main-classes") {
    cleanWorkspace()
    val mbtJson = new MbtJsonBuilder(BuildInfo.scalaVersion)
      .addScalaLibrary()
      .addNamespace("core", List("src/"))
      .build()

    for {
      _ <- initialize(
        s"""|/.metals/mbt.json
            |$mbtJson
            |/src/ScalaMain.scala
            |package example
            |
            |object ScalaMain {
            |  def main(args: Array[String]): Unit = ()
            |}
            |/src/AppMain.scala
            |package example
            |
            |object AppMain extends App
            |/src/JavaMain.java
            |package example;
            |
            |public class JavaMain {
            |  public static void main(String[] args) {
            |  }
            |}
            |""".stripMargin
      )
      _ = assertConnectedToBuildServer("MBT")
      _ <- server.didOpen("src/ScalaMain.scala")
      _ <- server.didOpen("src/AppMain.scala")
      _ <- server.didOpen("src/JavaMain.java")
      _ <- server.server.buildTargetClasses.rebuildIndex(
        server.server.buildTargets.allBuildTargetIds
      )
      _ <- Future.sequence(
        List(
          assertCodeLenses(
            "src/ScalaMain.scala",
            """|package example
               |
               |<<run>><<debug>>
               |object ScalaMain {
               |  def main(args: Array[String]): Unit = ()
               |}
               |""".stripMargin,
          ),
          assertCodeLenses(
            "src/AppMain.scala",
            """|package example
               |
               |<<run>><<debug>>
               |object AppMain extends App
               |""".stripMargin,
          ),
          assertCodeLenses(
            "src/JavaMain.java",
            """|package example;
               |
               |public class JavaMain {
               |<<run>><<debug>>
               |  public static void main(String[] args) {
               |  }
               |}
               |""".stripMargin,
          ),
        )
      )
    } yield ()
  }

  test("discover-scala-test-classes") {
    cleanWorkspace()
    val mbtJson = new MbtJsonBuilder(BuildInfo.scalaVersion)
      .addScalaLibrary()
      .addDependency("org.scalameta", "munit", "0.7.29")
      .addNamespace("test", List("src/**"))
      .build()

    for {
      _ <- initialize(
        s"""|/.metals/mbt.json
            |$mbtJson
            |/src/MunitTest.scala
            |package example
            |
            |class MunitTest extends munit.FunSuite {
            |  test("sample-test") {
            |    assert(1 == 1)
            |  }
            |}
            |""".stripMargin
      )
      _ = assertConnectedToBuildServer("MBT")
      _ <- server.didOpen("src/MunitTest.scala")
      _ <- server.discoverTestSuites(List("src/MunitTest.scala"))
      _ <- server.didChangeConfiguration(
        userConfig
          .copy(testUserInterface = TestUserInterfaceKind.CodeLenses)
          .toString()
      )
      _ <- assertCodeLenses(
        "src/MunitTest.scala",
        """|package example
           |
           |<<test>><<debug test>>
           |class MunitTest extends munit.FunSuite {
           |<<test case>><<debug test case>>
           |  test("sample-test") {
           |    assert(1 == 1)
           |  }
           |}
           |""".stripMargin,
        minExpectedLenses = 4,
      )
    } yield ()
  }

  test("discover-java-test-classes") {
    cleanWorkspace()
    val mbtJson = new MbtJsonBuilder(BuildInfo.scalaVersion)
      .addScalaLibrary()
      .addJavaDependency("junit", "junit", "4.13.2")
      .addNamespace("test", List("src/**"))
      .build()

    for {
      _ <- initialize(
        s"""|/.metals/mbt.json
            |$mbtJson
            |/src/JunitTest.java
            |package example;
            |
            |import org.junit.Test;
            |import static org.junit.Assert.*;
            |
            |public class JunitTest {
            |  @Test
            |  public void sampleTest() {
            |    assertTrue(true);
            |  }
            |}
            |""".stripMargin
      )
      _ = assertConnectedToBuildServer("MBT")
      _ <- server.didOpen("src/JunitTest.java")
      discovered <- server.executeDiscoverMainClassesCommand(
        DebugDiscoveryParams(
          workspace.resolve("src/JunitTest.java").toURI.toString,
          "testFile",
        )
      )
      _ = assertEquals(discovered.getDataKind, "scala-test-suites")
      _ = assert(
        discovered.getData.toString.contains("example.JunitTest")
      )
      _ <- server.discoverTestSuites(List("src/JunitTest.java"))
      _ <- server.didChangeConfiguration(
        userConfig
          .copy(testUserInterface = TestUserInterfaceKind.CodeLenses)
          .toString()
      )
      _ <- assertCodeLenses(
        "src/JunitTest.java",
        """|package example;
           |
           |import org.junit.Test;
           |import static org.junit.Assert.*;
           |
           |<<test>><<debug test>>
           |public class JunitTest {
           |  @Test
           |<<test case>><<debug test case>>
           |  public void sampleTest() {
           |    assertTrue(true);
           |  }
           |}
           |""".stripMargin,
        minExpectedLenses = 4,
      )
    } yield ()
  }
}
