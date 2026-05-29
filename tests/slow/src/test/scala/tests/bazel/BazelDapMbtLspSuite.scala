package tests.bazel

import java.util.concurrent.TimeUnit

import scala.concurrent.Future
import scala.concurrent.duration._

import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.metals.AutoImportBuildKind
import scala.meta.internal.metals.Configs.JavaSymbolLoaderConfig
import scala.meta.internal.metals.Configs.ReferenceProviderConfig
import scala.meta.internal.metals.Configs.WorkspaceSymbolProviderConfig
import scala.meta.internal.metals.DebugUnresolvedTestClassParams
import scala.meta.internal.metals.JsonParser._
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.debug.DebugWorkspaceLayout
import scala.meta.internal.metals.mbt.MbtBuildServer
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.DebugSessionParamsDataKind
import ch.epfl.scala.bsp4j.ScalaMainClass
import tests.BaseDapSuite
import tests.BazelBuildLayout
import tests.BazelMbtTestInitializer

class BazelDapMbtLspSuite
    extends BaseDapSuite(
      "bazel-mbt-dap",
      BazelMbtTestInitializer,
      BazelBuildLayout,
    ) {

  private val bazelVersion = "8.2.1"

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

  private def pinMaven(workspace: AbsolutePath): Unit = {
    workspace.resolve("maven_install.json").touch()
    ShellRunner.runSync(
      List("bazel", "run", "@maven//:pin"),
      workspace,
      redirectErrorOutput = false,
      timeout = 1.minute,
    )
  }

  private def bazelWorkspaceLayout: String =
    """|/.bazelproject
       |targets:
       |    //...
       |
       |/app/BUILD
       |java_binary(
       |    name = "main",
       |    srcs = ["Main.java"],
       |    main_class = "app.Main",
       |)
       |
       |/app/Main.java
       |package app;
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

  test("bazel-mbt-test-session", maxRetry = 3) {
    client.selectedServer = Messages.ChooseBuildServer.mbt
    cleanWorkspace()

    for {
      _ <- initialize(
        BazelBuildLayout(
          bazelTestWorkspaceLayout,
          V.scala213,
          bazelVersion,
          List("junit:junit:4.13.2"),
        ),
        runAdditionalCommands = pinMaven,
      )
      _ <- server.headServer.connectionProvider.buildServerPromise.future
      _ <- server.didOpen("test/FooTest.java")
      _ <- awaitMbtTestClassDiscovery("test/FooTest.java")
      debugger <- server.startDebuggingUnresolved(
        new DebugUnresolvedTestClassParams("test.FooTest").toJson
      )
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assertContains(output, "OK (1 test)")
  }

  test("bazel-mbt-test-breakpoint") {
    client.selectedServer = Messages.ChooseBuildServer.mbt
    cleanWorkspace()

    val debugLayout = DebugWorkspaceLayout(
      """|/test/FooTest2.java
         |package test;
         |
         |import org.junit.Test;
         |import static org.junit.Assert.*;
         |
         |public class FooTest2 {
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

    val testBuildFile =
      """|/test/BUILD
         |java_test(
         |    name = "FooTest2",
         |    srcs = ["FooTest2.java"],
         |    test_class = "test.FooTest2",
         |    deps = ["@maven//:junit_junit"],
         |)
         |""".stripMargin

    for {
      _ <- initialize(
        BazelBuildLayout(
          s"""|/.bazelproject
              |targets:
              |    //...
              |
              |$testBuildFile
              |${debugLayout.toString}
              |""".stripMargin,
          V.scala213,
          bazelVersion,
          List("junit:junit:4.13.2"),
        ),
        runAdditionalCommands = pinMaven,
      )
      _ <- server.headServer.connectionProvider.buildServerPromise.future
      _ <- server.didOpen("test/FooTest2.java")
      _ <- awaitMbtTestClassDiscovery("test/FooTest2.java")
      debugger <- server.startDebuggingUnresolved(
        new DebugUnresolvedTestClassParams("test.FooTest2").toJson,
        navigator,
      )
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- setBreakpoints(debugger, debugLayout)
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assertContains(output, "OK (1 test)")
  }

  test("bazel-mbt-debug-session") {
    client.selectedServer = Messages.ChooseBuildServer.mbt
    cleanWorkspace()

    val mainClass = new ScalaMainClass(
      "app.Main",
      List("Bar").asJava,
      List("-Dproperty=Foo").asJava,
    )

    for {
      _ <- initialize(
        BazelBuildLayout(
          bazelWorkspaceLayout,
          V.scala213,
          bazelVersion,
        )
      )
      _ <- server.headServer.connectionProvider.buildServerPromise.future
      _ <- server.didOpen("app/Main.java")
      debugger <- server.startDebugging(
        "//app",
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

  private def bazelTestWorkspaceLayout: String =
    """|/.bazelproject
       |targets:
       |    //...
       |
       |/test/BUILD
       |java_test(
       |    name = "FooTest",
       |    srcs = ["FooTest.java"],
       |    test_class = "test.FooTest",
       |    deps = ["@maven//:junit_junit"],
       |)
       |
       |/test/FooTest.java
       |package test;
       |
       |import org.junit.Test;
       |import static org.junit.Assert.*;
       |
       |public class FooTest {
       |  @Test
       |  public void testAddition() {
       |    assertEquals(4, 2 + 2);
       |  }
       |}
       |""".stripMargin

}
