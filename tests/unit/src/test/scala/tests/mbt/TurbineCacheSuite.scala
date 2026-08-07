package tests.mbt

import java.nio.file.Files

import scala.meta.internal.metals.AutoImportBuildKind
import scala.meta.internal.metals.Configs
import scala.meta.internal.metals.Configs.ReferenceProviderConfig
import scala.meta.internal.metals.Configs.WorkspaceSymbolProviderConfig
import scala.meta.internal.metals.Directories
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.mbt.MbtBuildServer

import tests.BaseLspSuite
import tests.BuildInfo
import tests.MbtJsonBuilder
import tests.TestHovers

class TurbineCacheSuite extends BaseLspSuite("turbine-cache") with TestHovers {

  override def userConfig: UserConfiguration = super.userConfig.copy(
    fallbackScalaVersion = Some(BuildInfo.scalaVersion),
    presentationCompilerDiagnostics = true,
    buildOnChange = false,
    buildOnFocus = false,
    workspaceSymbolProvider = WorkspaceSymbolProviderConfig.mbt,
    referenceProvider = ReferenceProviderConfig.mbt,
    javaTurbineCache = Configs.TurbineCacheConfig.enabled,
    preferredBuildServer = Some(MbtBuildServer.name),
    automaticImportBuild = AutoImportBuildKind.All,
  )

  override def initializeGitRepo: Boolean = true

  private val testName = "turbine-cache-persists-across-restarts"

  private val javaFile = "src/com/example/Hello.java"
  private val scalaFile = "src/com/example/Main.scala"

  private val javaContents =
    """|package com.example;
       |
       |import com.google.common.collect.ImmutableList;
       |
       |public class Hello {
       |  public static class Person {
       |    public final String name;
       |
       |    public Person(String name) {
       |      this.name = name;
       |    }
       |  }
       |
       |  public static String greet(String name) {
       |    return "Hello, " + name + "!";
       |  }
       |
       |  public static ImmutableList<String> names() {
       |    return ImmutableList.of("Alice", "Bob");
       |  }
       |
       |  public static Person person(String name) {
       |    return new Person(name);
       |  }
       |}
       |""".stripMargin

  private val scalaContents =
    """|package com.example
       |
       |object Main {
       |  def run(): String = Hello.greet("World")
       |  def names = Hello.names()
       |  def person: Hello.Person = Hello.person("Alice")
       |}
       |""".stripMargin

  private val immutableListHover =
    """|```java
       |public abstract class com.google.common.collect.ImmutableList<E> extends com.google.common.collect.ImmutableCollection<E> implements java.util.List<E>, java.util.RandomAccess
       |```
       |""".stripMargin

  private val greetHover =
    """|```scala
       |def greet(name: String): String
       |```
       |""".stripMargin.hover

  private val personTypeHover =
    """|```scala
       |class Person: Hello.Person
       |```
       |""".stripMargin.hover

  private val personInnerHover =
    """|```java
       |public static class com.example.Hello.Person
       |```
       |""".stripMargin

  private def assertHovers() =
    for {
      _ <- server.assertHover(
        scalaFile,
        """|package com.example
           |
           |object Main {
           |  def run(): String = Hello.gre@@et("World")
           |  def names = Hello.names()
           |  def person: Hello.Person = Hello.person("Alice")
           |}
           |""".stripMargin,
        greetHover,
      )
      // Verify the library dependency is discoverable via turbine classpath.
      _ <- server.assertHover(
        javaFile,
        javaContents.replace("ImmutableList.of", "Immutabl@@eList.of"),
        immutableListHover,
      )
      // Verify nested/inner classes resolve from Scala and Java.
      _ <- server.assertHover(
        scalaFile,
        """|package com.example
           |
           |object Main {
           |  def run(): String = Hello.greet("World")
           |  def names = Hello.names()
           |  def person: Hello.Per@@son = Hello.person("Alice")
           |}
           |""".stripMargin,
        personTypeHover,
      )
      _ <- server.assertHover(
        javaFile,
        javaContents.replace(
          "public static Person person",
          "public static Per@@son person",
        ),
        personInnerHover,
      )
    } yield ()

  test(testName) {
    cleanWorkspace()
    // Fetch Guava first, then prepend scala-library (addJavaDependency replaces the list).
    val mbtJson = new MbtJsonBuilder(BuildInfo.scalaVersion)
      .addJavaDependency("com.google.guava", "guava", "33.5.0-jre")
      .addScalaLibrary()
      .addNamespace("core", List("src/**"))
      .build()

    for {
      _ <- initialize(
        s"""|/.metals/mbt.json
            |$mbtJson
            |/$javaFile
            |$javaContents
            |/$scalaFile
            |$scalaContents
            |""".stripMargin
      )

      _ <- server.didOpen(javaFile)
      _ <- server.didOpen(scalaFile)
      _ = assertNoDiagnostics()
      _ <- assertHovers()

      cachePath = workspace.resolve(Directories.turbineCache)
      _ = assert(
        Files.exists(cachePath.toNIO),
        s"Turbine cache file should exist at $cachePath after compilation",
      )

      cacheSize = Files.size(cachePath.toNIO)
      _ = assert(cacheSize > 0, "Turbine cache file should not be empty")

      _ = cancelServer()
      _ = newServer(testName)

      _ <- server.initialize()
      _ <- server.initialized()
      _ <- server.didChangeConfiguration(userConfig.toString)
      _ = server.assertBuildServerConnection()

      _ <- server.didOpen(javaFile)
      _ <- server.didOpen(scalaFile)
      _ = assertNoDiagnostics()
      // Symbols (including library + inner classes) must still resolve after cache load.
      _ <- assertHovers()

      _ = assert(
        Files.exists(cachePath.toNIO),
        "Turbine cache file should still exist after restart",
      )
    } yield ()
  }

  private val dirtyFilesTestName = "turbine-cache-handles-dirty-files"

  private val javaContentsWithNewMethod =
    """|package com.example;
       |
       |import com.google.common.collect.ImmutableList;
       |
       |public class Hello {
       |  public static class Person {
       |    public final String name;
       |
       |    public Person(String name) {
       |      this.name = name;
       |    }
       |  }
       |
       |  public static String greet(String name) {
       |    return "Hello, " + name + "!";
       |  }
       |
       |  public static ImmutableList<String> names() {
       |    return ImmutableList.of("Alice", "Bob");
       |  }
       |
       |  public static Person person(String name) {
       |    return new Person(name);
       |  }
       |
       |  public static String farewell(String name) {
       |    return "Goodbye, " + name + "!";
       |  }
       |}
       |""".stripMargin

  private val scalaContentsWithFarewell =
    """|package com.example
       |
       |object Main {
       |  def run(): String = Hello.greet("World")
       |  def names = Hello.names()
       |  def person: Hello.Person = Hello.person("Alice")
       |  def bye(): String = Hello.farewell("World")
       |}
       |""".stripMargin

  private val farewellHover =
    """|```scala
       |def farewell(name: String): String
       |```
       |""".stripMargin.hover

  test(dirtyFilesTestName) {
    cleanWorkspace()
    val mbtJson = new MbtJsonBuilder(BuildInfo.scalaVersion)
      .addJavaDependency("com.google.guava", "guava", "33.5.0-jre")
      .addScalaLibrary()
      .addNamespace("core", List("src/**"))
      .build()

    for {
      // Step 1: Initialize workspace and create cache
      _ <- initialize(
        s"""|/.metals/mbt.json
            |$mbtJson
            |/$javaFile
            |$javaContents
            |/$scalaFile
            |$scalaContents
            |""".stripMargin
      )

      _ <- server.didOpen(javaFile)
      _ <- server.didOpen(scalaFile)
      _ = assertNoDiagnostics()
      _ <- assertHovers()

      cachePath = workspace.resolve(Directories.turbineCache)
      _ = assert(
        Files.exists(cachePath.toNIO),
        s"Turbine cache file should exist at $cachePath after compilation",
      )

      // Step 2: Restart server
      _ = cancelServer()
      _ = newServer(dirtyFilesTestName)

      // Step 3: Modify the Java file on disk BEFORE starting the server
      // This simulates uncommitted changes (dirty files)
      _ = Files.writeString(
        workspace.resolve(javaFile).toNIO,
        javaContentsWithNewMethod,
      )
      _ = Files.writeString(
        workspace.resolve(scalaFile).toNIO,
        scalaContentsWithFarewell,
      )

      _ <- server.initialize()
      _ <- server.initialized()
      _ <- server.didChangeConfiguration(userConfig.toString)
      _ = server.assertBuildServerConnection()

      _ <- server.didOpen(javaFile)
      _ <- server.didOpen(scalaFile)
      _ = assertNoDiagnostics()

      // Step 4: Verify that the NEW method is visible via hover
      // This proves that dirty files are added to sourcepath and take precedence
      // over the cached compiled classes
      _ <- server.assertHover(
        scalaFile,
        """|package com.example
           |
           |object Main {
           |  def run(): String = Hello.greet("World")
           |  def names = Hello.names()
           |  def person: Hello.Person = Hello.person("Alice")
           |  def bye(): String = Hello.fare@@well("World")
           |}
           |""".stripMargin,
        farewellHover,
      )

      // Original methods should still work
      _ <- server.assertHover(
        scalaFile,
        """|package com.example
           |
           |object Main {
           |  def run(): String = Hello.gre@@et("World")
           |  def names = Hello.names()
           |  def person: Hello.Person = Hello.person("Alice")
           |  def bye(): String = Hello.farewell("World")
           |}
           |""".stripMargin,
        greetHover,
      )
    } yield ()
  }
}
