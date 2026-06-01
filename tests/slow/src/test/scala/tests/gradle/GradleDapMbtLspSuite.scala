package tests.gradle

import java.util.concurrent.TimeUnit

import scala.concurrent.Future
import scala.jdk.CollectionConverters._

import scala.meta.internal.metals.AutoImportBuildKind
import scala.meta.internal.metals.Configs.JavaSymbolLoaderConfig
import scala.meta.internal.metals.Configs.ReferenceProviderConfig
import scala.meta.internal.metals.Configs.WorkspaceSymbolProviderConfig
import scala.meta.internal.metals.DebugUnresolvedTestClassParams
import scala.meta.internal.metals.JsonParser._
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.debug.DebugWorkspaceLayout
import scala.meta.internal.metals.mbt.MbtBuildServer

import ch.epfl.scala.bsp4j.DebugSessionParamsDataKind
import ch.epfl.scala.bsp4j.ScalaMainClass
import tests.BaseDapSuite
import tests.QuickBuildInitializer
import tests.QuickBuildLayout

class GradleDapMbtLspSuite
    extends BaseDapSuite(
      "gradle-mbt-dap",
      QuickBuildInitializer,
      QuickBuildLayout,
    ) {

  override def userConfig: UserConfiguration =
    super.userConfig.copy(
      presentationCompilerDiagnostics = true,
      buildOnChange = false,
      buildOnFocus = false,
      workspaceSymbolProvider = WorkspaceSymbolProviderConfig.mbt,
      javaSymbolLoader = JavaSymbolLoaderConfig.turbineClasspath,
      referenceProvider = ReferenceProviderConfig.mbt,
      preferredBuildServer = Some(MbtBuildServer.name),
      automaticImportBuild = AutoImportBuildKind.All,
    )

  override def initializeGitRepo: Boolean = true

  private def awaitMbtTestClassDiscovery(testFile: String): Future[Unit] = {
    val metals = server.server
    val targets = metals.buildTargets.allBuildTargetIds
    for {
      _ <- server.didSave(testFile)
      _ <- metals.mbtSymbolSearch.recompileTurbineClasspath()
      _ <- metals.buildTargetClasses.rebuildIndex(targets)
      _ <- server.waitFor(TimeUnit.SECONDS.toMillis(10))
    } yield ()
  }

  private def buildGradle: String =
    """|plugins {
       |    id 'java'
       |}
       |repositories {
       |    mavenCentral()
       |}
       |""".stripMargin

  private def buildGradleWithJunit: String =
    """|plugins {
       |    id 'java'
       |}
       |repositories {
       |    mavenCentral()
       |}
       |dependencies {
       |    testImplementation 'junit:junit:4.13.2'
       |}
       |test {
       |    testLogging {
       |        events "passed", "failed", "skipped"
       |    }
       |}
       |""".stripMargin

  test("gradle-mbt-test-session") {
    client.selectedServer = Messages.ChooseBuildServer.mbt
    cleanWorkspace()

    for {
      _ <- initialize(
        s"""|/build.gradle
            |$buildGradleWithJunit
            |/src/test/java/a/FooTest2.java
            |package a;
            |
            |import org.junit.Test;
            |import static org.junit.Assert.*;
            |
            |public class FooTest2 {
            |  @Test
            |  public void testAddition() {
            |    assertEquals(4, 2 + 2);
            |  }
            |}
            |""".stripMargin
      )
      _ <- server.headServer.connectionProvider.buildServerPromise.future
      _ <- server.didOpen("src/test/java/a/FooTest2.java")
      _ <- awaitMbtTestClassDiscovery("src/test/java/a/FooTest2.java")
      debugger <- server.startDebuggingUnresolved(
        new DebugUnresolvedTestClassParams("a.FooTest2").toJson
      )
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assertContains(output, "BUILD SUCCESSFUL")
  }

  test("gradle-mbt-test-breakpoint") {
    client.selectedServer = Messages.ChooseBuildServer.mbt
    cleanWorkspace()

    val debugLayout = DebugWorkspaceLayout(
      """|/src/test/java/a/FooTest.java
         |package a;
         |
         |import org.junit.Test;
         |import static org.junit.Assert.*;
         |
         |public class FooTest {
         |  @Test
         |  public void testAddition() {
         |    int result = 2 + 2;
         |>>  assertEquals(4, result);
         |  }
         |}
         |""".stripMargin,
      workspace,
    )

    val navigator = navigateExpectedBreakpoints(debugLayout)

    for {
      _ <- initialize(
        s"""|/build.gradle
            |$buildGradleWithJunit
            |${debugLayout.toString}
            |""".stripMargin
      )
      _ <- server.headServer.connectionProvider.buildServerPromise.future
      _ <- server.didOpen("src/test/java/a/FooTest.java")
      _ <- awaitMbtTestClassDiscovery("src/test/java/a/FooTest.java")
      debugger <- server.startDebuggingUnresolved(
        new DebugUnresolvedTestClassParams("a.FooTest").toJson,
        navigator,
      )
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- setBreakpoints(debugger, debugLayout)
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assertContains(output, "BUILD SUCCESSFUL")
  }

  test("gradle-mbt-debug-session") {
    client.selectedServer = Messages.ChooseBuildServer.mbt
    cleanWorkspace()

    val mainClass = new ScalaMainClass(
      "a.Main",
      List("Bar").asJava,
      List("-Dproperty=Foo").asJava,
    )

    for {
      _ <- initialize(
        s"""|/build.gradle
            |$buildGradle
            |/src/main/java/a/Main.java
            |package a;
            |
            |public class Main {
            |  public static void main(String[] args) {
            |    String foo = System.getProperty("property", "");
            |    String bar = args.length > 0 ? args[0] : "";
            |    System.out.print(foo + bar);
            |    System.exit(0);
            |  }
            |}
            |""".stripMargin
      )
      _ <- server.headServer.connectionProvider.buildServerPromise.future
      _ <- server.didOpen("src/main/java/a/Main.java")
      debugger <- server.startDebugging(
        "gradle-mbt-debug-session",
        DebugSessionParamsDataKind.SCALA_MAIN_CLASS,
        mainClass,
      )
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assertContains(output, "FooBar")
  }
}
