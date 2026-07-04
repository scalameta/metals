package tests.bazel

import scala.concurrent.duration._

import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.metals.AutoImportBuildKind
import scala.meta.internal.metals.Configs.JavaSymbolLoaderConfig
import scala.meta.internal.metals.Configs.ReferenceProviderConfig
import scala.meta.internal.metals.Configs.WorkspaceSymbolProviderConfig
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.mbt.MbtBuildServer
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.io.AbsolutePath

import tests.BaseLspSuite
import tests.BaseMbtSuite
import tests.BazelBuildLayout
import tests.MbtTestInitializer
import tests.TestHovers

/**
 * End-to-end: Bazel workspace → MBT import (`bazel query` + `.metals/mbt.json`)
 * → [[MbtBuildServer]] → Scala hover.
 */
class BazelMbtLspSuite
    extends BaseLspSuite("bazel-mbt", MbtTestInitializer)
    with TestHovers
    with BaseMbtSuite {

  private val bazelVersion = "8.2.1"

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

  override def initializeGitRepo: Boolean = true

  private val catsVersion = "2.13.0"
  private val jsoupVersion = "1.21.1"

  /** Same targets as [[BazelLspSuite]], plus a project view so MBT import scopes `bazel query`. */
  private def bazelWorkspaceLayout: String = {
    val projectView =
      """/.bazelproject
        |targets:
        |    //...
        |
        |""".stripMargin
    val rulesAndSources1 = rulesAndSources("maven")
    val rulesAndSources2 =
      s"""|
          |/app/BUILD
          |load("@rules_scala//scala:scala.bzl", "scala_binary")
          |
          |scala_binary(
          |    name = "hello",
          |    srcs = ["Main.scala", "Decode.scala"],
          |    main_class = "main",
          |    deps = ["//core:hello_lib"],
          |)
          |
          |/app/Main.scala
          |import core.Hello
          |
          |object Main {
          |  def msg = new Hello().hello
          |}
          |
          |/app/Decode.scala
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
    projectView + rulesAndSources1 + rulesAndSources2
  }

  private val mavenDeps: List[String] = List(
    s"org.typelevel:cats-core_2.13:$catsVersion"
  )

  private val singleCoreMbtJson: String =
    """|{
       |  "dependencyModules": [
       |    {
       |      "id": "org.scala-lang:scala-library:2.13.16",
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
       |    "//core": {
       |      "sources": [
       |        "core/Bar.scala",
       |        "core/Hello.scala"
       |      ],
       |      "scalacOptions": [
       |        "-deprecation"
       |      ],
       |      "javacOptions": [],
       |      "dependencyModules": [
       |        "org.scala-lang:scala-library:2.13.16",
       |        "org.typelevel:cats-core_2.13:2.13.0",
       |        "org.typelevel:cats-kernel_2.13:2.13.0"
       |      ],
       |      "scalaVersion": "2.13.16",
       |      "dependsOn": [],
       |      "classDirectories": []
       |    }
       |  },
       |  "uncheckedSources": []
       |}""".stripMargin

  private val javaMavenDeps: List[String] = List(
    s"org.jsoup:jsoup:$jsoupVersion"
  )

  private def javaBazelWorkspaceLayout: String =
    """|/.bazelproject
       |targets:
       |    //...
       |
       |/lib/BUILD
       |java_library(
       |    name = "parser",
       |    srcs = ["Parser.java"],
       |    visibility = ["//visibility:public"],
       |    javacopts = ["-Xlint:unchecked"],
       |    deps = ["@maven//:org_jsoup_jsoup"],
       |)
       |
       |/lib/Parser.java
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
       |
       |/app/BUILD
       |java_binary(
       |    name = "main",
       |    srcs = ["Main.java"],
       |    main_class = "app.Main",
       |    deps = ["//lib:parser"],
       |)
       |
       |/app/Main.java
       |package app;
       |
       |import lib.Parser;
       |
       |public class Main {
       |  public static void main(String[] args) {
       |    String title = new Parser().title("<html><head><title>Hello</title></head></html>");
       |    System.out.println(title);
       |  }
       |}
       |""".stripMargin

  private def filegroupBazelWorkspaceLayout: String =
    """|/.bazelproject
       |targets:
       |    //...
       |
       |/lib/BUILD
       |load("@rules_scala//scala:scala.bzl", "scala_library")
       |
       |filegroup(
       |    name = "library_sources",
       |    srcs = [
       |        "Library.scala",
       |        "More.scala",
       |    ],
       |)
       |
       |scala_library(
       |    name = "library",
       |    srcs = [":library_sources"],
       |)
       |
       |/lib/Library.scala
       |package lib
       |
       |class Library {
       |  def message: String = "hello"
       |}
       |
       |/lib/More.scala
       |package lib
       |
       |class More {
       |  def library = new Library().message
       |}
       |""".stripMargin

  private def pinMaven(workspace: AbsolutePath): Unit =
    pinHub("maven")(workspace)

  private def pinHub(hubName: String)(workspace: AbsolutePath): Unit = {
    workspace.resolve("maven_install.json").touch()
    ShellRunner.runSync(
      List("bazel", "run", s"@$hubName//:pin"),
      workspace,
      redirectErrorOutput = false,
      timeout = 1.minute,
    )
  }

  private def rulesAndSources(hubName: String) =
    s"""|/core/BUILD
        |load("@rules_scala//scala:scala.bzl", "scala_library")
        |
        |scala_library(
        |    name = "hello_lib",
        |    srcs = ["Hello.scala", "Bar.scala"],
        |    visibility = ["//visibility:public"],
        |    scalacopts = ["-deprecation"],
        |    deps = ["@$hubName//:org_typelevel_cats_core_2_13"],
        |)
        |
        |/core/Hello.scala
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
        |/core/Bar.scala
        |package core
        |
        |class Bar {
        |  def bar: String = "bar"
        |  def hi = new Hello().hello
        |}
        |""".stripMargin

  private def customHubWorkspaceLayout(hubName: String): String = {
    val projectView =
      """|/.bazelproject
         |targets:
         |    //core/...
         |
         |""".stripMargin

    projectView + rulesAndSources(hubName)
  }

  private val skewHubA = "cats_a"
  private val skewHubB = "cats_b"
  private val skewVersionA = "2.12.0"
  private val skewVersionB = "2.13.0"

  private def multiHubWorkspaceLayout: String = {
    val projectView =
      """|/.bazelproject
         |targets:
         |    //...
         |
         |""".stripMargin
    val coreA =
      s"""|/core_a/BUILD
          |load("@rules_scala//scala:scala.bzl", "scala_library")
          |
          |scala_library(
          |    name = "cats_a",
          |    srcs = ["A.scala"],
          |    deps = ["@$skewHubA//:org_typelevel_cats_core_2_13"],
          |)
          |
          |/core_a/A.scala
          |package a
          |
          |import cats.syntax.all._
          |
          |class A {
          |  def value: Option[Int] = 1.some
          |}
          |""".stripMargin
    val coreB =
      s"""|/core_b/BUILD
          |load("@rules_scala//scala:scala.bzl", "scala_library")
          |
          |scala_library(
          |    name = "cats_b",
          |    srcs = ["B.scala"],
          |    deps = ["@$skewHubB//:org_typelevel_cats_core_2_13"],
          |)
          |
          |/core_b/B.scala
          |package b
          |
          |import cats.syntax.all._
          |
          |class B {
          |  def value: Option[Int] = 2.some
          |}
          |""".stripMargin
    projectView + coreA + coreB
  }

  private def pinHubs(hubNames: List[String])(workspace: AbsolutePath): Unit = {
    for (hubName <- hubNames)
      workspace.resolve(s"${hubName}_install.json").touch()
    // Pinning two hubs fetches two sets of Maven artifacts, so allow more time
    // than the single-hub `pinHub`.
    for (hubName <- hubNames)
      ShellRunner.runSync(
        List("bazel", "run", s"@$hubName//:pin"),
        workspace,
        redirectErrorOutput = false,
        timeout = 3.minutes,
      )
  }

  test("bazel-import-mbt-multi-hub-version-skew") {
    client.selectedServer = Messages.ChooseBuildServer.mbt
    cleanWorkspace()
    for {
      _ <- initialize(
        BazelBuildLayout.multiHub(
          multiHubWorkspaceLayout,
          V.scala213,
          bazelVersion,
          List(
            skewHubA -> List(s"org.typelevel:cats-core_2.13:$skewVersionA"),
            skewHubB -> List(s"org.typelevel:cats-core_2.13:$skewVersionB"),
          ),
        ),
        runAdditionalCommands = pinHubs(List(skewHubA, skewHubB)),
      )
      _ <- server.headServer.connectionProvider.buildServerPromise.future
      mbtFile = workspace.resolve(".metals/mbt.json").readText
      // Each namespace references the cats (and transitively cats-kernel /
      // scala-library) version from its own hub: //core_a -> 2.12.0, //core_b ->
      // 2.13.0, even though both hubs share the coordinate
      // `org_typelevel_cats_core_2_13`.
      _ = assertNoDiff(
        escapeMbtFile(mbtFile),
        s"""|{
            |  "dependencyModules": [
            |    {
            |      "id": "org.scala-lang:scala-library:2.13.14",
            |      "jar": "<jar-path>",
            |      "sources": "<sources-path>"
            |    },
            |    {
            |      "id": "org.scala-lang:scala-library:2.13.16",
            |      "jar": "<jar-path>",
            |      "sources": "<sources-path>"
            |    },
            |    {
            |      "id": "org.typelevel:cats-core_2.13:2.12.0",
            |      "jar": "<jar-path>",
            |      "sources": "<sources-path>"
            |    },
            |    {
            |      "id": "org.typelevel:cats-core_2.13:2.13.0",
            |      "jar": "<jar-path>",
            |      "sources": "<sources-path>"
            |    },
            |    {
            |      "id": "org.typelevel:cats-kernel_2.13:2.12.0",
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
            |    "//core_a": {
            |      "sources": [
            |        "core_a/A.scala"
            |      ],
            |      "scalacOptions": [],
            |      "javacOptions": [],
            |      "dependencyModules": [
            |        "org.scala-lang:scala-library:2.13.14",
            |        "org.typelevel:cats-core_2.13:2.12.0",
            |        "org.typelevel:cats-kernel_2.13:2.12.0"
            |      ],
            |      "scalaVersion": "2.13.14",
            |      "dependsOn": [],
            |      "classDirectories": []
            |    },
            |    "//core_b": {
            |      "sources": [
            |        "core_b/B.scala"
            |      ],
            |      "scalacOptions": [],
            |      "javacOptions": [],
            |      "dependencyModules": [
            |        "org.scala-lang:scala-library:2.13.16",
            |        "org.typelevel:cats-core_2.13:2.13.0",
            |        "org.typelevel:cats-kernel_2.13:2.13.0"
            |      ],
            |      "scalaVersion": "2.13.14",
            |      "dependsOn": [],
            |      "classDirectories": []
            |    }
            |  },
            |  "uncheckedSources": []
            |}""".stripMargin,
      )
    } yield ()
  }

  test("bazel-import-mbt-server-hover") {
    client.selectedServer = Messages.ChooseBuildServer.mbt
    cleanWorkspace()
    for {
      _ <- initialize(
        BazelBuildLayout(
          bazelWorkspaceLayout,
          V.scala213,
          bazelVersion,
          mavenDeps,
        ),
        runAdditionalCommands = pinMaven,
      )
      _ <- server.headServer.connectionProvider.buildServerPromise.future
      mbtFile = workspace.resolve(".metals/mbt.json").readText
      _ = assertNoDiff(
        escapeMbtFile(mbtFile),
        s"""|{
            |  "dependencyModules": [
            |    {
            |      "id": "org.scala-lang:scala-library:2.13.16",
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
            |    "//core": {
            |      "sources": [
            |        "core/Bar.scala",
            |        "core/Hello.scala"
            |      ],
            |      "scalacOptions": [
            |        "-deprecation"
            |      ],
            |      "javacOptions": [],
            |      "dependencyModules": [
            |        "org.scala-lang:scala-library:2.13.16",
            |        "org.typelevel:cats-core_2.13:2.13.0",
            |        "org.typelevel:cats-kernel_2.13:2.13.0"
            |      ],
            |      "scalaVersion": "2.13.16",
            |      "dependsOn": [],
            |      "classDirectories": []
            |    },
            |    "//app": {
            |      "sources": [
            |        "app/Decode.scala",
            |        "app/Main.scala"
            |      ],
            |      "scalacOptions": [],
            |      "javacOptions": [],
            |      "dependencyModules": [
            |        "org.scala-lang:scala-library:2.13.16",
            |        "org.typelevel:cats-core_2.13:2.13.0",
            |        "org.typelevel:cats-kernel_2.13:2.13.0"
            |      ],
            |      "scalaVersion": "2.13.16",
            |      "dependsOn": [
            |        "//core"
            |      ],
            |      "classDirectories": ["<classDirectories-path>"],
            |      "configurations": [
            |        "//app:hello"
            |      ]
            |    }
            |  },
            |  "uncheckedSources": []
            |}""".stripMargin,
      )
      _ <- server.didOpen("core/Hello.scala")
      _ <- server.assertHover(
        "core/Hello.scala",
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
           |""".stripMargin.hover,
      )
      _ <- server.assertHover(
        "core/Hello.scala",
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
           |""".stripMargin.hover,
      )
    } yield ()
  }

  test("bazel-import-mbt-filegroup-srcs") {
    cleanWorkspace()
    for {
      _ <- initialize(
        BazelBuildLayout(
          filegroupBazelWorkspaceLayout,
          V.scala213,
          bazelVersion,
        )
      )
      _ <- server.headServer.connectionProvider.buildServerPromise.future
      mbtFile = workspace.resolve(".metals/mbt.json").readText
      _ = assertNoDiff(
        escapeMbtFile(mbtFile),
        s"""|{
            |  "dependencyModules": [],
            |  "namespaces": {
            |    "//lib": {
            |      "sources": [
            |        "lib/Library.scala",
            |        "lib/More.scala"
            |      ],
            |      "scalacOptions": [],
            |      "javacOptions": [],
            |      "dependencyModules": [],
            |      "scalaVersion": "2.13.18",
            |      "dependsOn": [],
            |      "classDirectories": []
            |    }
            |  },
            |  "uncheckedSources": []
            |}""".stripMargin,
      )
    } yield ()
  }

  test("bazel-import-mbt-single-target") {
    client.selectedServer = Messages.ChooseBuildServer.mbt
    cleanWorkspace()
    for {
      _ <- initialize(
        BazelBuildLayout(
          s"""|$bazelWorkspaceLayout
              |
              |/.bazelproject
              |targets:
              |    //core/...
              |
              |""".stripMargin,
          V.scala213,
          bazelVersion,
          mavenDeps,
        ),
        runAdditionalCommands = pinMaven,
      )
      _ <- server.headServer.connectionProvider.buildServerPromise.future
      mbtFile = workspace.resolve(".metals/mbt.json").readText
      _ = assertNoDiff(escapeMbtFile(mbtFile), singleCoreMbtJson)
      _ <- server.didOpen("core/Hello.scala")
      _ <- server.assertHover(
        "core/Hello.scala",
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
           |""".stripMargin.hover,
      )
      _ <- server.assertHover(
        "core/Hello.scala",
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
           |""".stripMargin.hover,
      )
    } yield ()
  }

  test("bazel-import-mbt-java-workspace") {
    client.selectedServer = Messages.ChooseBuildServer.mbt
    cleanWorkspace()
    for {
      _ <- initialize(
        BazelBuildLayout(
          javaBazelWorkspaceLayout,
          V.scala213,
          bazelVersion,
          javaMavenDeps,
        ),
        runAdditionalCommands = pinMaven,
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
            |    "//lib": {
            |      "sources": [
            |        "lib/Parser.java"
            |      ],
            |      "scalacOptions": [],
            |      "javacOptions": [
            |        "-Xlint:unchecked"
            |      ],
            |      "dependencyModules": [
            |        "org.jsoup:jsoup:1.21.1"
            |      ],
            |      "scalaVersion": "2.13.18",
            |      "dependsOn": [],
            |      "classDirectories": []
            |    },
            |    "//app": {
            |      "sources": [
            |        "app/Main.java"
            |      ],
            |      "scalacOptions": [],
            |      "javacOptions": [],
            |      "dependencyModules": [
            |        "org.jsoup:jsoup:1.21.1"
            |      ],
            |      "scalaVersion": "2.13.18",
            |      "dependsOn": [
            |        "//lib"
            |      ],
            |      "classDirectories": ["<classDirectories-path>"],
            |      "configurations": [
            |        "//app:main"
            |      ]
            |    }
            |  },
            |  "uncheckedSources": []
            |}""".stripMargin,
      )
      _ <- server.didOpen("lib/Parser.java")
      _ <- server.didFocus("lib/Parser.java")
      _ <- server.didSave("lib/Parser.java")
      _ <- server.assertHover(
        "lib/Parser.java",
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
           |""".stripMargin.hover,
      )
    } yield ()
  }

  test("bazel-import-mbt-workspace-namespace-choice") {
    client.selectedServer = Messages.ChooseBuildServer.mbt
    cleanWorkspace()
    client.chooseBazelMbtNamespaceMode =
      Messages.BazelMbtNamespaceChoice.workspace
    for {
      _ <- initialize(
        BazelBuildLayout(
          bazelWorkspaceLayout,
          V.scala213,
          bazelVersion,
          mavenDeps,
        ),
        runAdditionalCommands = pinMaven,
      )
      _ <- server.headServer.connectionProvider.buildServerPromise.future
      mbtFile = workspace.resolve(".metals/mbt.json").readText
      _ = assertContains(
        client.workspaceMessageRequests,
        Messages.BazelMbtNamespaceChoice.params().getMessage(),
      )
      _ = assertNoDiff(
        escapeMbtFile(mbtFile),
        s"""|{
            |  "dependencyModules": [
            |    {
            |      "id": "org.scala-lang:scala-library:2.13.16",
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
            |    "bazel-workspace": {
            |      "sources": [
            |        "app/Decode.scala",
            |        "app/Main.scala",
            |        "core/Bar.scala",
            |        "core/Hello.scala"
            |      ],
            |      "scalacOptions": [],
            |      "javacOptions": [],
            |      "dependencyModules": [
            |        "org.scala-lang:scala-library:2.13.16",
            |        "org.typelevel:cats-core_2.13:2.13.0",
            |        "org.typelevel:cats-kernel_2.13:2.13.0"
            |      ],
            |      "scalaVersion": "2.13.16",
            |      "dependsOn": [],
            |      "classDirectories": ["<classDirectories-path>"],
            |      "configurations": [
            |        "//app:hello"
            |      ]
            |    }
            |  },
            |  "uncheckedSources": []
            |}""".stripMargin,
      )
    } yield ()
  }

  test("bazel-import-mbt-custom-maven-hub") {
    val hub = "custom_maven"
    client.selectedServer = Messages.ChooseBuildServer.mbt
    cleanWorkspace()
    for {
      _ <- initialize(
        BazelBuildLayout(
          customHubWorkspaceLayout(hub),
          V.scala213,
          bazelVersion,
          mavenDeps,
          Some(hub),
        ),
        runAdditionalCommands = pinHub(hub),
      )
      _ <- server.headServer.connectionProvider.buildServerPromise.future
      mbtFile = workspace.resolve(".metals/mbt.json").readText
      // Identical to the single-target result: the hub name
      // does not appear in the imported model.
      _ = assertNoDiff(escapeMbtFile(mbtFile), singleCoreMbtJson)
    } yield ()
  }

}
