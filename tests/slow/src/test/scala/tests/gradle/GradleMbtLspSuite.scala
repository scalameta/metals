package tests.gradle

import scala.util.Properties

import scala.meta.internal.metals.AutoImportBuildKind
import scala.meta.internal.metals.Configs.JavaSymbolLoaderConfig
import scala.meta.internal.metals.Configs.ReferenceProviderConfig
import scala.meta.internal.metals.Configs.WorkspaceSymbolProviderConfig
import scala.meta.internal.metals.InitializationOptions
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.TestUserInterfaceKind
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.mbt.MbtBuildServer
import scala.meta.internal.metals.testProvider.TestExplorerEvent
import scala.meta.internal.metals.{BuildInfo => V}

import tests.BaseLspSuite
import tests.BaseMbtSuite

class GradleMbtLspSuite
    extends BaseLspSuite("gradle-import")
    with BaseMbtSuite {

  override def userConfig: UserConfiguration =
    super.userConfig.copy(
      fallbackScalaVersion = Some(
        "2.13.12"
      ), // This should not be used if the target has a scala version
      presentationCompilerDiagnostics = true,
      buildOnChange = false,
      buildOnFocus = false,
      workspaceSymbolProvider = WorkspaceSymbolProviderConfig.mbt,
      javaSymbolLoader = JavaSymbolLoaderConfig.turbineClasspath,
      referenceProvider = ReferenceProviderConfig.mbt,
      preferredBuildServer = Some(MbtBuildServer.name),
      automaticImportBuild = AutoImportBuildKind.All,
      testUserInterface = TestUserInterfaceKind.TestExplorer,
    )

  override def initializeGitRepo: Boolean = true

  override protected def initializationOptions: Some[InitializationOptions] =
    Some(
      InitializationOptions.Default
        .copy(testExplorerProvider = Some(true))
    )

  test("basic") {
    client.selectedServer = Messages.ChooseBuildServer.mbt
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
            |    implementation 'org.typelevel:cats-core_2.13:2.13.0'
            |}
            |/src/main/scala/core/Hello.scala
            |package core
            |
            |import cats.syntax.all._
            |
            |class Hello {
            |  def hello: String = "Hello"
            |  def catOption: Option[Int] = 1.some
            |
            |}
            |
            |/src/main/scala/core/Bar.scala
            |package core
            |
            |class Bar {
            |  def bar: String = "bar"
            |  def hi = new Hello().hello
            |}
            |
            |/src/main/scala/app/Main.scala
            |import core.Hello
            |
            |object Main {
            |  def msg = new Hello().hello
            |}
            |
            |/src/main/scala/app/Decode.scala
            |package app
            |
            |class Decode {
            | def decoded = this
            |}
            |
            |object Decode {
            | def decode: String = "decode"
            |}
            |""".stripMargin
      )
      _ <- server.headServer.connectionProvider.buildServerPromise.future
      mbtFile = workspace.resolve(".metals/mbt.json").readText
      _ = assertNoDiff(
        escapeMbtFile(mbtFile),
        s"""|{
            |  "dependencyModules": [
            |    {
            |      "id": "org.scala-lang:scala-library:2.13.18",
            |      "jar": "<jar-path>",
            |      "sources": "<sources-path>"
            |    },
            |    {
            |      "id": "org.typelevel:cats-core_2.13:2.13.0",
            |      "jar": "<jar-path>",
            |      "sources": "<sources-path>"
            |    },
            |    {
            |      "id": "org.typelevel:cats-kernel_2.13:2.13.0",
            |      "jar": "<jar-path>",
            |      "sources": "<sources-path>"
            |    }
            |  ],
            |  "namespaces": {
            |    "basic": {
            |      "sources": [
            |        "src/main/java",
            |        "src/main/scala"
            |      ],
            |      "scalacOptions": [],
            |      "javacOptions": [],
            |      "dependencyModules": [
            |        "org.scala-lang:scala-library:2.13.18",
            |        "org.typelevel:cats-core_2.13:2.13.0",
            |        "org.typelevel:cats-kernel_2.13:2.13.0"
            |      ],
            |      "javaHome": "file://${Properties.javaHome.replace("\\", "/")}/",
            |      "classDirectories": [
            |        "build/classes/java/main",
            |        "build/classes/scala/main"
            |      ],
            |      "projectPath": ":"
            |    },
            |    "basic:test": {
            |      "sources": [
            |        "src/test/java",
            |        "src/test/scala"
            |      ],
            |      "scalacOptions": [],
            |      "javacOptions": [],
            |      "dependencyModules": [
            |        "org.scala-lang:scala-library:2.13.18",
            |        "org.typelevel:cats-core_2.13:2.13.0",
            |        "org.typelevel:cats-kernel_2.13:2.13.0"
            |      ],
            |      "javaHome": "file://${Properties.javaHome.replace("\\", "/")}/",
            |      "dependsOn": [
            |        "basic"
            |      ],
            |      "classDirectories": [
            |        "build/classes/java/test",
            |        "build/classes/scala/test"
            |      ],
            |      "projectPath": ":"
            |    }
            |  }
            |}
            |""".stripMargin,
      )
      _ <- server.didOpen("src/main/scala/core/Hello.scala")
      _ <- server.assertHover(
        "src/main/scala/core/Hello.scala",
        s"""|package core
            |
            |import cats.syntax.all._
            |
            |class Hello {
            |  def hel@@lo: String = "Hello"
            |  def catOption: Option[Int] = 1.some
            |
            |}
            |""".stripMargin,
        """|```scala
           |def hello: String
           |```
           |""".stripMargin,
      )
      _ <- server.assertHover(
        "src/main/scala/core/Hello.scala",
        s"""|package core
            |
            |import cats.syntax.all._
            |
            |class Hello {
            |  def hello: String = "Hello"
            |  def catOption: Option[Int] = 1.so@@me
            |
            |}
            |""".stripMargin,
        """|```scala
           |def some: Option[Int]
           |```
           |Wrap a value in `Some`.
           |
           |`3.some` is equivalent to `Some(3)`, but the former will have an inferred
           |return type of `Option[Int]` while the latter will have `Some[Int]`.
           |
           |Example:
           |
           |```
           |scala> import cats.syntax.all._
           |scala> 3.some
           |res0: Option[Int] = Some(3)
           |```
           |""".stripMargin,
      )
    } yield ()
  }

  test("plain-java") {
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
            |dependencies {
            |    implementation 'org.jsoup:jsoup:1.21.1'
            |}
            |/src/main/java/lib/Parser.java
            |package lib;
            |
            |import org.jsoup.Jsoup;
            |import org.jsoup.nodes.Document;
            |
            |public class Parser {
            |  public String title(String html) {
            |    Document document = Jsoup.parse(html);
            |    return document.title();
            |  }
            |}
            |""".stripMargin
      )
      _ <- server.headServer.connectionProvider.buildServerPromise.future
      mbtFile = workspace.resolve(".metals/mbt.json").readText
      _ = assertNoDiff(
        escapeMbtFile(mbtFile),
        s"""|{
            |  "dependencyModules": [
            |    {
            |      "id": "org.jsoup:jsoup:1.21.1",
            |      "jar": "<jar-path>",
            |      "sources": "<sources-path>"
            |    }
            |  ],
            |  "namespaces": {
            |    "plain-java": {
            |      "sources": [
            |        "src/main/java"
            |      ],
            |      "scalacOptions": [],
            |      "javacOptions": [],
            |      "dependencyModules": [
            |        "org.jsoup:jsoup:1.21.1"
            |      ],
            |      "javaHome": "file://${Properties.javaHome.replace("\\", "/")}/",
            |      "classDirectories": ["<classDirectories-path>"],
            |      "projectPath": ":"
            |    },
            |    "plain-java:test": {
            |      "sources": [
            |        "src/test/java"
            |      ],
            |      "scalacOptions": [],
            |      "javacOptions": [],
            |      "dependencyModules": [
            |        "org.jsoup:jsoup:1.21.1"
            |      ],
            |      "javaHome": "file://${Properties.javaHome.replace("\\", "/")}/",
            |      "dependsOn": [
            |        "plain-java"
            |      ],
            |      "classDirectories": ["<classDirectories-path>"],
            |      "projectPath": ":"
            |    }
            |  }
            |}
            |""".stripMargin,
      )
      _ <- server.didOpen("src/main/java/lib/Parser.java")
      _ <- server.didFocus("src/main/java/lib/Parser.java")
      _ <- server.assertHover(
        "src/main/java/lib/Parser.java",
        s"""|package lib;
            |
            |import org.jsoup.Jsoup;
            |import org.jsoup.nodes.Document;
            |
            |public class Parser {
            |  public String title(String html) {
            |    Document document = Jsoup.parse(html);
            |    return document.tit@@le();
            |  }
            |}
            |""".stripMargin,
        """|```java
           |public java.lang.String title()
           |```
           |Get the string contents of the document's `title` element.
           |
           |**Returns:** Trimmed title, or empty string if none set.
           |""".stripMargin,
      )
    } yield ()
  }

  test("composite-build") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/settings.gradle
            |rootProject.name = 'main-project'
            |includeBuild 'plugin-lib'
            |/build.gradle
            |plugins {
            |    id 'java'
            |}
            |repositories {
            |    mavenCentral()
            |}
            |dependencies {
            |    implementation 'com.example:plugin-lib:0.1'
            |}
            |/src/main/java/app/App.java
            |package app;
            |
            |import lib.Parser;
            |
            |public class App {
            |  public String run(String html) {
            |    return new Parser().title(html);
            |  }
            |}
            |/plugin-lib/settings.gradle
            |rootProject.name = 'plugin-lib'
            |/plugin-lib/build.gradle
            |plugins {
            |    id 'java'
            |}
            |group = 'com.example'
            |version = '0.1'
            |repositories {
            |    mavenCentral()
            |}
            |dependencies {
            |    implementation 'org.jsoup:jsoup:1.21.1'
            |    testImplementation 'junit:junit:4.13.2'
            |}
            |/plugin-lib/src/main/java/lib/Parser.java
            |package lib;
            |
            |import org.jsoup.Jsoup;
            |import org.jsoup.nodes.Document;
            |
            |public class Parser {
            |  public String title(String html) {
            |    Document document = Jsoup.parse(html);
            |    return document.title();
            |  }
            |}
            |/plugin-lib/src/test/java/lib/ParserTest.java
            |package lib;
            |
            |import org.junit.Test;
            |import static org.junit.Assert.*;
            |
            |public class ParserTest {
            |  @Test
            |  public void parsesTitle() {
            |    assertEquals("hello", new Parser().title("<title>hello</title>"));
            |  }
            |}
            |""".stripMargin
      )
      _ <- server.headServer.connectionProvider.buildServerPromise.future
      mbtFile = workspace.resolve(".metals/mbt.json").readText
      _ = assert(
        mbtFile.contains(""""plugin-lib""""),
        s"Expected 'plugin-lib' namespace in mbt.json but got:\n$mbtFile",
      )
      _ = assert(
        mbtFile.contains(""""plugin-lib/src/main/java""""),
        s"Expected 'plugin-lib/src/main/java' sources in mbt.json but got:\n$mbtFile",
      )
      _ = assert(
        mbtFile.contains(""""plugin-lib/src/test/java""""),
        s"Expected 'plugin-lib/src/test/java' sources in mbt.json but got:\n$mbtFile",
      )
      _ = assert(
        mbtFile
          .replaceAll("\\s+", "")
          .contains(""""dependsOn":["plugin-lib"]"""),
        s"Expected 'main-project' to depend on 'plugin-lib' in mbt.json but got:\n$mbtFile",
      )
      _ <- server.didOpen("plugin-lib/src/main/java/lib/Parser.java")
      _ <- server.didFocus("plugin-lib/src/main/java/lib/Parser.java")
      _ <- server.assertHover(
        "plugin-lib/src/main/java/lib/Parser.java",
        s"""|package lib;
            |
            |import org.jsoup.Jsoup;
            |import org.jsoup.nodes.Document;
            |
            |public class Parser {
            |  public String title(String html) {
            |    Document document = Jsoup.parse(html);
            |    return document.tit@@le();
            |  }
            |}
            |""".stripMargin,
        """|```java
           |public java.lang.String title()
           |```
           |Get the string contents of the document's `title` element.
           |
           |**Returns:** Trimmed title, or empty string if none set.
           |""".stripMargin,
      )
      _ <- server.didOpen("plugin-lib/src/test/java/lib/ParserTest.java")
      discovered <- server.discoverTestSuites(
        List("plugin-lib/src/test/java/lib/ParserTest.java")
      )
      _ = assert(
        discovered.exists(update =>
          update.events.asScala.exists {
            case e: TestExplorerEvent.AddTestSuite =>
              e.fullyQualifiedClassName == "lib.ParserTest"
            case _ => false
          }
        ),
        s"Expected ParserTest to be discovered but got:\n$discovered",
      )
    } yield ()
  }

  test("code-lens-java-main") {
    client.selectedServer = Messages.ChooseBuildServer.mbt
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
            |  public static void main(String[] args) {
            |    System.out.println("Hello!");
            |  }
            |}
            |""".stripMargin
      )
      _ <- server.headServer.connectionProvider.buildServerPromise.future
      _ <- server.didOpen("src/main/java/a/Main.java")
      obtained <- server
        .codeLensesText("src/main/java/a/Main.java")(maxRetries = 4)
        .recover { case _: NoSuchElementException =>
          server.textContents("src/main/java/a/Main.java")
        }
      _ = assertNoDiff(
        obtained,
        """|package a;
           |
           |public class Main {
           |<<run>><<debug>>
           |  public static void main(String[] args) {
           |    System.out.println("Hello!");
           |  }
           |}
           |""".stripMargin,
      )
    } yield ()
  }

}
