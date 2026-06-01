package tests.mbt

import scala.concurrent.Future

import scala.meta.internal.metals.AutoImportBuildKind
import scala.meta.internal.metals.Configs.ReferenceProviderConfig
import scala.meta.internal.metals.Configs.WorkspaceSymbolProviderConfig
import scala.meta.internal.metals.TestUserInterfaceKind
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.mbt.MbtBuildServer

import tests.BaseCodeLensLspSuite
import tests.BuildInfo
import tests.MbtJsonBuilder

class MbtRunDebugLspSuite extends BaseCodeLensLspSuite("mbt-run-debug") {

  override def userConfig: UserConfiguration =
    super.userConfig.copy(
      buildOnChange = false,
      buildOnFocus = false,
      workspaceSymbolProvider = WorkspaceSymbolProviderConfig.mbt,
      referenceProvider = ReferenceProviderConfig.mbt,
      preferredBuildServer = Some(MbtBuildServer.name),
      automaticImportBuild = AutoImportBuildKind.All,
      testUserInterface = TestUserInterfaceKind.CodeLenses,
    )

  override def initializeGitRepo: Boolean = true

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
      _ <- server.discoverTestSuites(List("src/JunitTest.java"))
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
