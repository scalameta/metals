package tests.bazel

import scala.jdk.CollectionConverters._

import scala.meta.internal.metals.AutoImportBuildKind
import scala.meta.internal.metals.Configs.JavaSymbolLoaderConfig
import scala.meta.internal.metals.Configs.ReferenceProviderConfig
import scala.meta.internal.metals.Configs.WorkspaceSymbolProviderConfig
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.mbt.MbtBuildServer
import scala.meta.internal.metals.{BuildInfo => V}

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
}
