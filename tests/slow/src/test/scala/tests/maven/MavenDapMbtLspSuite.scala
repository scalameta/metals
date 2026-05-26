package tests.maven

import scala.jdk.CollectionConverters._

import scala.meta.internal.metals.AutoImportBuildKind
import scala.meta.internal.metals.Configs.JavaSymbolLoaderConfig
import scala.meta.internal.metals.Configs.ReferenceProviderConfig
import scala.meta.internal.metals.Configs.WorkspaceSymbolProviderConfig
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.mbt.MbtBuildServer

import ch.epfl.scala.bsp4j.DebugSessionParamsDataKind
import ch.epfl.scala.bsp4j.ScalaMainClass
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

  private def mvnw: String =
    """|#!/bin/sh
       |set -eu
       |
       |if [ "$#" -gt 0 ] && [ "$1" = "-q" ]; then
       |  shift
       |fi
       |
       |case " $* " in
       |  *" install "*)
       |    mkdir -p target/classes
       |    javac -d target/classes src/main/java/a/Main.java
       |    exit 0
       |    ;;
       |esac
       |
       |exec_args=""
       |for arg in "$@"; do
       |  case "$arg" in
       |    -Dexec.args=*) exec_args=${arg#-Dexec.args=} ;;
       |  esac
       |done
       |
       |if [ -z "$exec_args" ]; then
       |  echo "missing -Dexec.args" >&2
       |  exit 1
       |fi
       |
       |exec_args=$(printf '%s' "$exec_args" | sed "s|%classpath|target/classes|g")
       |eval "exec java $exec_args"
       |""".stripMargin

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
            |/mvnw
            |$mvnw
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
      _ = workspace.resolve("mvnw").toFile.setExecutable(true)
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
}
