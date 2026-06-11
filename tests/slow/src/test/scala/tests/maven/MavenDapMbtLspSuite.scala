package tests.maven

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
import scala.meta.internal.metals.debug.DebugStep
import scala.meta.internal.metals.debug.DebugWorkspaceLayout
import scala.meta.internal.metals.debug.Stoppage
import scala.meta.internal.metals.mbt.MbtBuildServer

import ch.epfl.scala.bsp4j.DebugSessionParamsDataKind
import ch.epfl.scala.bsp4j.ScalaMainClass
import org.eclipse.lsp4j.debug.EvaluateResponse
import tests.BaseDapSuite
import tests.QuickBuildInitializer
import tests.QuickBuildLayout

class MavenDapMbtLspSuite
    extends BaseDapSuite(
      "maven-mbt-dap",
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

  private def pom: String =
    """|<project xmlns="http://maven.apache.org/POM/4.0.0"
       |  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
       |  xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/maven-v4_0_0.xsd">
       |  <modelVersion>4.0.0</modelVersion>
       |  <groupId>com.example</groupId>
       |  <artifactId>maven-mbt-debug</artifactId>
       |  <version>1.0.0</version>
       |  <build>
       |    <sourceDirectory>src/main/java</sourceDirectory>
       |    <plugins>
       |      <plugin>
       |        <groupId>org.apache.maven.plugins</groupId>
       |        <artifactId>maven-compiler-plugin</artifactId>
       |        <version>3.13.0</version>
       |        <configuration>
       |          <release>17</release>
       |        </configuration>
       |      </plugin>
       |    </plugins>
       |  </build>
       |</project>""".stripMargin

  private def pomWithJunit: String =
    """|<project xmlns="http://maven.apache.org/POM/4.0.0"
       |  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
       |  xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/maven-v4_0_0.xsd">
       |  <modelVersion>4.0.0</modelVersion>
       |  <groupId>com.example</groupId>
       |  <artifactId>maven-mbt-test</artifactId>
       |  <version>1.0.0</version>
       |  <dependencies>
       |    <dependency>
       |      <groupId>junit</groupId>
       |      <artifactId>junit</artifactId>
       |      <version>4.13.2</version>
       |      <scope>test</scope>
       |    </dependency>
       |  </dependencies>
       |  <build>
       |    <testSourceDirectory>src/test/java</testSourceDirectory>
       |    <plugins>
       |      <plugin>
       |        <groupId>org.apache.maven.plugins</groupId>
       |        <artifactId>maven-compiler-plugin</artifactId>
       |        <version>3.13.0</version>
       |        <configuration>
       |          <release>17</release>
       |        </configuration>
       |      </plugin>
       |    </plugins>
       |  </build>
       |</project>""".stripMargin

  test("maven-mbt-test-session") {
    client.selectedServer = Messages.ChooseBuildServer.mbt
    cleanWorkspace()

    for {
      _ <- initialize(
        s"""|/pom.xml
            |$pomWithJunit
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
        new DebugUnresolvedTestClassParams("a.FooTest2", noDebug = false).toJson
      )
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assertContains(
      output,
      "Tests run: 1, Failures: 0, Errors: 0, Skipped: 0",
    )
  }

  test("maven-mbt-test-breakpoint") {
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
        s"""|/pom.xml
            |$pomWithJunit
            |${debugLayout.toString}
            |""".stripMargin
      )
      _ <- server.headServer.connectionProvider.buildServerPromise.future
      _ <- server.didOpen("src/test/java/a/FooTest.java")
      _ <- awaitMbtTestClassDiscovery("src/test/java/a/FooTest.java")
      debugger <- server.startDebuggingUnresolved(
        new DebugUnresolvedTestClassParams("a.FooTest", noDebug = false).toJson,
        navigator,
      )
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- setBreakpoints(debugger, debugLayout)
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assertContains(
      output,
      "Tests run: 1, Failures: 0, Errors: 0, Skipped: 0",
    )
  }

  test("maven-mbt-debug-session") {
    client.selectedServer = Messages.ChooseBuildServer.mbt
    cleanWorkspace()

    val mainClass = new ScalaMainClass(
      "a.Main",
      List("Bar").asJava,
      List("-Dproperty=Foo").asJava,
    )

    for {
      _ <- initialize(
        s"""|/pom.xml
            |$pom
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
        "com.example:maven-mbt-debug:1.0.0",
        DebugSessionParamsDataKind.SCALA_MAIN_CLASS,
        mainClass,
      )
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assertNoDiff(output, "FooBar")
  }

  test("maven-mbt-breakpoint") {
    client.selectedServer = Messages.ChooseBuildServer.mbt
    cleanWorkspace()

    val debugLayout = DebugWorkspaceLayout(
      """|/src/main/java/a/Main.java
         |package a;
         |
         |public class Main {
         |  public static void main(String[] args) {
         |    String message = "Hello";
         |>>  System.out.println(message);
         |    System.exit(0);
         |  }
         |}
         |""".stripMargin,
      workspace,
    )

    var evaluationResult: Option[EvaluateResponse] = None
    val navigator = new Stoppage.Handler {
      override def apply(stoppage: Stoppage): DebugStep = {
        val frameId = stoppage.frame.info.getId
        DebugStep.Evaluate(
          "message",
          frameId,
          response => evaluationResult = Some(response),
          DebugStep.Continue,
        )
      }
      override def shutdown = scala.concurrent.Future.unit
    }

    val mainClass = new ScalaMainClass(
      "a.Main",
      List().asJava,
      List().asJava,
    )

    for {
      _ <- initialize(
        s"""|/pom.xml
            |$pom
            |${debugLayout.toString}
            |""".stripMargin
      )
      _ <- server.headServer.connectionProvider.buildServerPromise.future
      _ <- server.didOpen("src/main/java/a/Main.java")
      debugger <- server.startDebugging(
        "com.example:maven-mbt-debug:1.0.0",
        DebugSessionParamsDataKind.SCALA_MAIN_CLASS,
        mainClass,
        navigator,
      )
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- setBreakpoints(debugger, debugLayout)
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield {
      assertNoDiff(output, "Hello\n")
      assert(
        evaluationResult.isDefined,
        "Expression evaluation should have occurred",
      )
      assertNoDiff(evaluationResult.get.getResult, "\"Hello\"")
    }
  }
}
