package tests.gradle

import scala.util.Properties

import scala.meta.internal.metals.AutoImportBuildKind
import scala.meta.internal.metals.Configs.JavaSymbolLoaderConfig
import scala.meta.internal.metals.Configs.ReferenceProviderConfig
import scala.meta.internal.metals.Configs.WorkspaceSymbolProviderConfig
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.mbt.MbtBuildServer
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
            |        "src/main/scala",
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
            |      "javaHome": "file://${Properties.javaHome.replace("\\", "/")}/"
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
            |        "src/main/java",
            |        "src/test/java"
            |      ],
            |      "scalacOptions": [],
            |      "javacOptions": [],
            |      "dependencyModules": [
            |        "org.jsoup:jsoup:1.21.1"
            |      ],
            |      "javaHome": "file://${Properties.javaHome.replace("\\", "/")}/"
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
           |""".stripMargin,
      )
    } yield ()
  }

}
