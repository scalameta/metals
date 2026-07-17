package tests.bazel

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

import scala.concurrent.duration._
import scala.util.Using

import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.metals.AutoImportBuildKind
import scala.meta.internal.metals.Configs.FallbackClasspathConfig
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
import tests.ScriptsAssertions
import tests.TestHovers

/**
 * End-to-end LSP coverage for the Bazel MBT toolchain and hidden-jar fixes:
 * srcjar source materialization ([[scala.meta.internal.metals.mbt.importer.BazelSrcjarSources]]),
 * compiler-classpath toolchain jars ([[scala.meta.internal.metals.mbt.importer.ScalaToolchainModules]]),
 * workspace fallback Scala version ([[scala.meta.internal.metals.mbt.importer.BazelEffectiveScalaVersionResolver]]),
 * and generated `java_proto_library` jars ([[scala.meta.internal.metals.mbt.importer.BazelGeneratedProtoModules]]).
 */
class BazelMbtToolchainLspSuite
    extends BaseLspSuite("bazel-mbt-toolchain", MbtTestInitializer)
    with TestHovers
    with ScriptsAssertions
    with BaseMbtSuite {

  private val bazelVersion = "8.2.1"

  // Set by `bazel-mbt-fallback-classpath-dedup` only: routes the fallback
  // presentation compiler classpath through the MBT dedup path (the default
  // `all-3rdparty` BSP branch shadows it) and keeps the fallback Scala
  // version at the built-in one so no external mtags are resolved.
  private var useMbtFallbackClasspath = false

  override def userConfig: UserConfiguration = {
    val base = super.userConfig.copy(
      fallbackScalaVersion = Some(
        "2.13.12"
      ), // This should not be used if the workspace pins a scala version
      presentationCompilerDiagnostics = true,
      buildOnChange = false,
      buildOnFocus = false,
      workspaceSymbolProvider = WorkspaceSymbolProviderConfig.mbt,
      javaSymbolLoader = JavaSymbolLoaderConfig.turbineClasspath,
      referenceProvider = ReferenceProviderConfig.mbt,
      preferredBuildServer = Some(MbtBuildServer.name),
      automaticImportBuild = AutoImportBuildKind.All,
    )
    if (useMbtFallbackClasspath)
      base.copy(
        fallbackClasspath = FallbackClasspathConfig.mbt,
        fallbackScalaVersion = Some(V.scala213),
      )
    else base
  }

  override def initializeGitRepo: Boolean = true

  private def writeZip(
      workspace: AbsolutePath,
      relativePath: String,
      entries: (String, String)*
  ): Unit =
    writeZipBytes(
      workspace.resolve(relativePath).toNIO,
      entries.map { case (name, content) =>
        name -> content.getBytes(StandardCharsets.UTF_8)
      }: _*
    )

  private def writeZipBytes(
      out: Path,
      entries: (String, Array[Byte])*
  ): Unit = {
    Files.createDirectories(out.getParent)
    Using.resource(new ZipOutputStream(Files.newOutputStream(out))) { zip =>
      for ((name, bytes) <- entries) {
        zip.putNextEntry(new ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
      }
    }
  }

  private def srcjarWorkspaceLayout: String =
    """|/.bazelproject
       |targets:
       |    //...
       |
       |/lib/BUILD
       |load("@rules_scala//scala:scala.bzl", "scala_library")
       |
       |scala_library(
       |    name = "generated",
       |    srcs = ["generated.srcjar"],
       |)
       |
       |scala_library(
       |    name = "consumer",
       |    srcs = ["Consumer.scala"],
       |    deps = [":generated"],
       |)
       |
       |/lib/Consumer.scala
       |package lib
       |
       |object Consumer {
       |  val greeting: String = Generated.line
       |}
       |""".stripMargin

  test("bazel-mbt-srcjar-sources-navigation") {
    client.selectedServer = Messages.ChooseBuildServer.mbt
    cleanWorkspace()
    for {
      _ <- initialize(
        BazelBuildLayout(srcjarWorkspaceLayout, V.scala213, bazelVersion),
        runAdditionalCommands = { workspace =>
          writeZip(
            workspace,
            "lib/generated.srcjar",
            "lib/Generated.scala" ->
              """|package lib
                 |
                 |object Generated {
                 |  def line: String = "generated"
                 |}
                 |""".stripMargin,
          )
        },
      )
      _ <- server.headServer.connectionProvider.buildServerPromise.future
      _ <- server.didOpen("lib/Consumer.scala")
      // The symbol is defined only inside `generated.srcjar`: hover works only
      // when the srcjar sources are materialized into the namespace.
      _ <- server.assertHover(
        "lib/Consumer.scala",
        """|package lib
           |
           |object Consumer {
           |  val greeting: String = Generated.li@@ne
           |}
           |""".stripMargin,
        """|```scala
           |def line: String
           |```
           |""".stripMargin.hover,
      )
      locations <- definitionsAt("lib/Consumer.scala", "Generated.li@@ne")
      _ = {
        assert(
          locations.length == 1,
          s"expected one definition location, got $locations",
        )
        val uri = locations.head.getUri
        assert(
          uri.contains(".metals/mbt-srcjar-sources/"),
          s"expected definition in the materialized srcjar sources, got $uri",
        )
        assert(
          uri.endsWith("/lib/Generated.scala"),
          s"expected definition in lib/Generated.scala, got $uri",
        )
      }
      _ = assertNoDiagnostics()
    } yield ()
  }

  private val compilerToolJava: String =
    """|package tool;
       |
       |import scala.tools.nsc.Global;
       |
       |public class CompilerTool {
       |  public String describe(Global global) {
       |    return global.toString();
       |  }
       |}
       |""".stripMargin

  private def compilerClasspathWorkspaceLayout: String =
    s"""|/.bazelproject
        |targets:
        |    //...
        |
        |/tool/BUILD
        |java_library(
        |    name = "tool",
        |    srcs = ["CompilerTool.java"],
        |    deps = ["@rules_scala//scala/private/toolchain_deps:scala_compile_classpath"],
        |)
        |
        |/tool/CompilerTool.java
        |$compilerToolJava
        |""".stripMargin

  test("bazel-mbt-java-target-compiler-classpath") {
    client.selectedServer = Messages.ChooseBuildServer.mbt
    cleanWorkspace()
    for {
      _ <- initialize(
        BazelBuildLayout(
          compilerClasspathWorkspaceLayout,
          V.scala213,
          bazelVersion,
        )
      )
      _ <- server.headServer.connectionProvider.buildServerPromise.future
      _ <- server.didOpen("tool/CompilerTool.java")
      _ <- server.didFocus("tool/CompilerTool.java")
      _ <- server.didSave("tool/CompilerTool.java")
      // The Java-only target gets scala-compiler jars only through the
      // `scala_compile_classpath` toolchain dependency: without them the file
      // has a false `package scala.tools.nsc does not exist` diagnostic and
      // `Global` does not resolve.
      _ = assertNoDiagnostics()
      hover <- server.hover(
        "tool/CompilerTool.java",
        compilerToolJava.replace(
          "import scala.tools.nsc.Global;",
          "import scala.tools.nsc.Glo@@bal;",
        ),
        workspace,
      )
      _ = assert(
        hover.contains("scala.tools.nsc.Global"),
        s"expected hover with the resolved Global class, got: $hover",
      )
      _ <- assertDefinitionAtLocation(
        "tool/CompilerTool.java",
        "import scala.tools.nsc.Glo@@bal;",
        s"dependencies/scala-compiler-${V.scala213}-sources.jar/scala/tools/nsc/Global.scala",
      )
    } yield ()
  }

  private val scala212Version = "2.12.20"

  private def scala212WorkspaceLayout: String =
    """|/.bazelproject
       |targets:
       |    //...
       |
       |/lib/BUILD
       |load("@rules_scala//scala:scala.bzl", "scala_library")
       |
       |scala_library(
       |    name = "lib",
       |    srcs = ["Lib.scala"],
       |)
       |
       |/lib/Lib.scala
       |package lib
       |
       |object Lib {
       |  val poem: String = List("a", "b").mkString("-")
       |}
       |""".stripMargin

  test("bazel-mbt-workspace-scala-version-over-user-fallback") {
    client.selectedServer = Messages.ChooseBuildServer.mbt
    cleanWorkspace()
    for {
      _ <- initialize(
        BazelBuildLayout(scala212WorkspaceLayout, scala212Version, bazelVersion)
      )
      _ <- server.headServer.connectionProvider.buildServerPromise.future
      _ <- server.didOpen("lib/Lib.scala")
      // No target declares `scala_version` and no maven module pins the
      // stdlib, so the rules_scala config repository is the only source of the
      // workspace's 2.12.20. `mkString` lives in `TraversableOnce` in 2.12
      // only (2.13 moved it to `IterableOnceOps`): landing in the 2.12.20
      // sources jar proves the workspace version won over the 2.13.12
      // `fallbackScalaVersion` user setting.
      _ <- assertDefinitionAtLocation(
        "lib/Lib.scala",
        """.mkStr@@ing("-")""",
        s"dependencies/scala-library-$scala212Version-sources.jar/scala/collection/TraversableOnce.scala",
      )
      _ = assertNoDiagnostics()
    } yield ()
  }

  private val generatedProtoJava: String =
    """|package io.test.gen;
       |
       |public final class Event {
       |  public static final String NAME = "ping";
       |
       |  private Event() {}
       |}
       |""".stripMargin

  private def generatedProtoWorkspaceLayout: String =
    """|/.bazelproject
       |targets:
       |    //...
       |
       |/proto/BUILD
       |proto_library(
       |    name = "event_proto",
       |    srcs = ["event.proto"],
       |)
       |
       |java_proto_library(
       |    name = "event_java_proto",
       |    visibility = ["//visibility:public"],
       |    deps = [":event_proto"],
       |)
       |
       |/proto/event.proto
       |syntax = "proto3";
       |package testproto;
       |message Ping { string name = 1; }
       |
       |/app/BUILD
       |java_library(
       |    name = "consumer",
       |    srcs = ["Consumer.java"],
       |    deps = ["//proto:event_java_proto"],
       |)
       |
       |/app/Consumer.java
       |package app;
       |
       |import io.test.gen.Event;
       |
       |public class Consumer {
       |  public String name() {
       |    return Event.NAME;
       |  }
       |}
       |""".stripMargin

  // Stands in for `bazel build //proto:event_java_proto` (would need a full
  // protoc setup): compiles the fixture's generated class and places the
  // `lib<name>-speed.jar` / `<name>-speed-src.jar` pair where the wrapped
  // `proto_library` would produce them under `bazel info bazel-bin`.
  private def createGeneratedProtoJars(workspace: AbsolutePath): Unit = {
    val bazelBin = ShellRunner
      .runSync(
        List("bazel", "info", "bazel-bin"),
        workspace,
        redirectErrorOutput = false,
        timeout = 3.minutes,
      )
      .map(_.trim)
      .filter(_.nonEmpty)
      .getOrElse(sys.error("could not resolve `bazel info bazel-bin`"))
    val tmp = Files.createTempDirectory("bazel-mbt-proto-fixture")
    val srcFile = tmp.resolve("io/test/gen/Event.java")
    Files.createDirectories(srcFile.getParent)
    Files.writeString(srcFile, generatedProtoJava)
    val classesDir = tmp.resolve("classes")
    Files.createDirectories(classesDir)
    val exitCode = javax.tools.ToolProvider
      .getSystemJavaCompiler()
      .run(null, null, null, "-d", classesDir.toString, srcFile.toString)
    require(exitCode == 0, "javac failed for the generated proto fixture")
    val protoBin = Paths.get(bazelBin).resolve("proto")
    writeZipBytes(
      protoBin.resolve("libevent_proto-speed.jar"),
      "io/test/gen/Event.class" -> Files.readAllBytes(
        classesDir.resolve("io/test/gen/Event.class")
      ),
    )
    writeZipBytes(
      protoBin.resolve("event_proto-speed-src.jar"),
      "io/test/gen/Event.java" -> generatedProtoJava.getBytes(
        StandardCharsets.UTF_8
      ),
    )
  }

  test("bazel-mbt-generated-proto-classes".ignore) {
    client.selectedServer = Messages.ChooseBuildServer.mbt
    cleanWorkspace()
    for {
      _ <- initialize(
        BazelBuildLayout(
          generatedProtoWorkspaceLayout,
          V.scala213,
          bazelVersion,
        ),
        runAdditionalCommands = createGeneratedProtoJars,
      )
      _ <- server.headServer.connectionProvider.buildServerPromise.future
      _ <- server.didOpen("app/Consumer.java")
      _ <- server.didFocus("app/Consumer.java")
      _ <- server.didSave("app/Consumer.java")
      // The generated classes exist only in the `java_proto_library` output
      // jar under bazel-bin: without it the consumer has a false
      // `package io.test.gen does not exist` diagnostic and `Event` does not
      // resolve.
      _ = assertNoDiagnostics()
      _ <- assertDefinitionAtLocation(
        "app/Consumer.java",
        "import io.test.gen.Ev@@ent;",
        "dependencies/event_proto-speed-src.jar/io/test/gen/Event.java",
      )
    } yield ()
  }

  private val hubA = "maven_a"
  private val hubB = "maven_b"
  private val catsVersionA = "2.12.0"
  private val catsVersionB = "2.13.0"

  private val orphanScala: String =
    """|package orphan
       |
       |import cats.data.NonEmptyVector
       |
       |object Orphan {
       |  val distinct: NonEmptyVector[Int] =
       |    NonEmptyVector.of(1, 2, 2).distinctBy(identity[Int])
       |}
       |""".stripMargin

  private val orphanJava: String =
    """|package orphan;
       |
       |import cats.data.NonEmptyVector;
       |import cats.kernel.Order;
       |import scala.Function1;
       |
       |public class OrphanBridge {
       |  static <A> Object dedup(NonEmptyVector<A> v, Function1<A, A> f, Order<A> o) {
       |    return v.distinctBy(f, o);
       |  }
       |}
       |""".stripMargin

  private def duplicateArtifactWorkspaceLayout: String =
    s"""|/.bazelproject
        |targets:
        |    //...
        |
        |/core_a/BUILD
        |load("@rules_scala//scala:scala.bzl", "scala_library")
        |
        |scala_library(
        |    name = "cats_a",
        |    srcs = ["A.scala"],
        |    deps = ["@$hubA//:org_typelevel_cats_core_2_13"],
        |)
        |
        |/core_a/A.scala
        |package a
        |
        |class A
        |
        |/core_b/BUILD
        |load("@rules_scala//scala:scala.bzl", "scala_library")
        |
        |scala_library(
        |    name = "cats_b",
        |    srcs = ["B.scala"],
        |    deps = ["@$hubB//:org_typelevel_cats_core_2_13"],
        |)
        |
        |/core_b/B.scala
        |package b
        |
        |class B
        |
        |/orphan/Orphan.scala
        |$orphanScala
        |
        |/orphan/OrphanBridge.java
        |$orphanJava
        |""".stripMargin

  private def pinHubs(hubNames: List[String])(workspace: AbsolutePath): Unit = {
    for (hubName <- hubNames)
      workspace.resolve(s"${hubName}_install.json").touch()
    // The first pin also starts the Bazel server and resolves the module
    // graph, so allow more time than the in-suite queries. A pin can fail
    // transiently while the cold repositories are still being fetched, so
    // retry until its lock file has content.
    for (hubName <- hubNames) {
      val lockFile = workspace.resolve(s"${hubName}_install.json")
      var attempts = 0
      while (lockFile.toFile.length() == 0 && attempts < 3) {
        attempts += 1
        ShellRunner.runSync(
          List("bazel", "run", s"@$hubName//:pin"),
          workspace,
          redirectErrorOutput = false,
          timeout = 8.minutes,
        )
      }
      require(
        lockFile.toFile.length() > 0,
        s"could not pin @$hubName after $attempts attempts",
      )
    }
  }

  test("bazel-mbt-fallback-classpath-dedup") {
    client.selectedServer = Messages.ChooseBuildServer.mbt
    useMbtFallbackClasspath = true
    cleanWorkspace()
    val result = for {
      _ <- initialize(
        BazelBuildLayout.multiHub(
          duplicateArtifactWorkspaceLayout,
          V.scala213,
          bazelVersion,
          List(
            hubA -> List(s"org.typelevel:cats-core_2.13:$catsVersionA"),
            hubB -> List(s"org.typelevel:cats-core_2.13:$catsVersionB"),
          ),
        ),
        runAdditionalCommands = pinHubs(List(hubA, hubB)),
      )
      _ <- server.headServer.connectionProvider.buildServerPromise.future
      // What a real client's file watcher delivers after the import writes
      // mbt.json: refreshes the MBT build the fallback classpath reads.
      _ <- server.didChangeWatchedFiles(".metals/mbt.json")
      // `orphan/Orphan.scala` belongs to no target, so it is served by the
      // fallback presentation compiler whose classpath is the deduplicated
      // MBT module list. The two hubs pin cats-core at 2.12.0 and 2.13.0, and
      // `NonEmptyVector.distinctBy` exists only in 2.13.0: without
      // deduplication the 2.12.0 jar (first on the classpath) shadows the
      // 2.13.0 one and the member does not resolve.
      _ <- server.didOpen("orphan/Orphan.scala")
      hover <- server.hover(
        "orphan/Orphan.scala",
        orphanScala.replace(".distinctBy(", ".distinct@@By("),
        workspace,
      )
      _ = assert(
        hover.contains("def distinctBy"),
        s"expected hover with the cats 2.13.0 `distinctBy` member, got: $hover",
      )
      _ = assertNoDiagnostics()
    } yield ()
    result.andThen { case _ => useMbtFallbackClasspath = false }
  }

  test("bazel-mbt-fallback-classpath-dedup-java") {
    client.selectedServer = Messages.ChooseBuildServer.mbt
    useMbtFallbackClasspath = true
    cleanWorkspace()
    val result = for {
      _ <- initialize(
        BazelBuildLayout.multiHub(
          duplicateArtifactWorkspaceLayout,
          V.scala213,
          bazelVersion,
          List(
            hubA -> List(s"org.typelevel:cats-core_2.13:$catsVersionA"),
            hubB -> List(s"org.typelevel:cats-core_2.13:$catsVersionB"),
          ),
        ),
        runAdditionalCommands = pinHubs(List(hubA, hubB)),
      )
      _ <- server.headServer.connectionProvider.buildServerPromise.future
      _ <- server.didChangeWatchedFiles(".metals/mbt.json")
      // `orphan/OrphanBridge.java` belongs to no target, so it is compiled by
      // the fallback *Java* presentation compiler. Its classpath is the same
      // MBT module list, deduplicated per artifact: with both cats-core jars
      // present javac binds the first (2.12.0) jar and the 2.13.0-only
      // `NonEmptyVector.distinctBy` fails with `cannot find symbol`.
      _ <- server.didOpen("orphan/OrphanBridge.java")
      _ <- server.didFocus("orphan/OrphanBridge.java")
      _ <- server.didSave("orphan/OrphanBridge.java")
      _ = assertNoDiagnostics()
      hover <- server.hover(
        "orphan/OrphanBridge.java",
        orphanJava.replace(".distinctBy(", ".distinct@@By("),
        workspace,
      )
      _ = assert(
        hover.contains("distinctBy"),
        s"expected hover with the cats 2.13.0 `distinctBy` member, got: $hover",
      )
    } yield ()
    result.andThen { case _ => useMbtFallbackClasspath = false }
  }

}
