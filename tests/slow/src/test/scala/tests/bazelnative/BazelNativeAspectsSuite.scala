package tests.bazelnative

import scala.meta.internal.builds.BazelDigest
import scala.meta.internal.builds.BazelNativeBuildTool
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j._

import scala.jdk.CollectionConverters._

import tests.BaseBazelNativeServerSuite
import tests.BaseImportSuite
import tests.BazelNativeBuildLayout
import tests.BazelNativeServerInitializer

/**
 * End-to-end slow tests that launch Bazel, run the BSP aspect, and verify
 * that metadata collected by the aspect is visible through BSP endpoints.
 */
class BazelNativeAspectsSuite
    extends BaseImportSuite(
      "bazel-native-aspects",
      BazelNativeServerInitializer,
    )
    with BaseBazelNativeServerSuite {

  lazy val buildTool: BazelNativeBuildTool =
    BazelNativeBuildTool(() => userConfig, workspace)

  val bazelVersion = "6.4.0"

  override def currentDigest(
      workspace: AbsolutePath
  ): Option[String] = BazelDigest.current(workspace)

  override def afterEach(context: AfterEach): Unit = {
    super.afterEach(context)
    cleanBazelServer()
  }

  private def bsp = server.server.bspSession
    .getOrElse(throw new AssertionError("BSP session not initialized"))
    .mainConnection

  private def findTarget(
      targets: java.util.List[BuildTarget],
      namePart: String,
  ): BuildTarget =
    targets.asScala
      .find(_.getDisplayName.contains(namePart))
      .getOrElse(
        throw new AssertionError(
          s"No target matching '$namePart' among: " +
            targets.asScala.map(_.getDisplayName).mkString(", ")
        )
      )

  private val workspaceLayout =
    s"""|/BUILD
        |load("@io_bazel_rules_scala//scala:scala_toolchain.bzl", "scala_toolchain")
        |load("@io_bazel_rules_scala//scala:scala.bzl", "scala_binary", "scala_library")
        |
        |scala_toolchain(
        |    name = "semanticdb_toolchain_impl",
        |    enable_semanticdb = True,
        |    semanticdb_bundle_in_jar = False,
        |    visibility = ["//visibility:public"],
        |)
        |
        |toolchain(
        |    name = "semanticdb_toolchain",
        |    toolchain = "semanticdb_toolchain_impl",
        |    toolchain_type = "@io_bazel_rules_scala//scala:toolchain_type",
        |    visibility = ["//visibility:public"],
        |)
        |
        |scala_library(
        |    name = "mylib",
        |    srcs = ["Lib.scala"],
        |)
        |
        |scala_binary(
        |    name = "myapp",
        |    srcs = ["Main.scala"],
        |    main_class = "myapp.Main",
        |    deps = [":mylib"],
        |)
        |
        |/Lib.scala
        |package mylib
        |
        |class Lib {
        |  def greet: String = "hello"
        |}
        |
        |/Main.scala
        |package myapp
        |
        |import mylib.Lib
        |
        |object Main {
        |  def main(args: Array[String]): Unit = {
        |    println(new Lib().greet)
        |  }
        |}
        |""".stripMargin

  test("aspect-build-targets") {
    cleanWorkspace()
    for {
      _ <- initialize(
        BazelNativeBuildLayout(workspaceLayout, "2.13.12", bazelVersion)
      )
      result <- bsp.workspaceBuildTargets()
      targets = result.getTargets.asScala.toList
    } yield {
      assert(targets.size >= 2, s"Expected >= 2 targets, got ${targets.size}")

      val lib = findTarget(result.getTargets, "mylib")
      assert(
        lib.getCapabilities.getCanCompile,
        "mylib should be compilable",
      )
      assert(
        !lib.getCapabilities.getCanRun,
        "mylib (library) should not be runnable",
      )

      val app = findTarget(result.getTargets, "myapp")
      assert(
        app.getCapabilities.getCanRun,
        "myapp (binary) should be runnable",
      )

      assert(
        lib.getDataKind == BuildTargetDataKind.SCALA,
        s"Expected SCALA data kind for mylib, got ${lib.getDataKind}",
      )
    }
  }

  test("aspect-sources") {
    cleanWorkspace()
    for {
      _ <- initialize(
        BazelNativeBuildLayout(workspaceLayout, "2.13.12", bazelVersion)
      )
      wbt <- bsp.workspaceBuildTargets()
      lib = findTarget(wbt.getTargets, "mylib")
      sourcesResult <- bsp.buildTargetSources(
        new SourcesParams(List(lib.getId).asJava)
      )
      items = sourcesResult.getItems.asScala.toList
    } yield {
      assert(items.nonEmpty, "Expected source items for mylib")
      val sourceUris = items.flatMap(_.getSources.asScala.map(_.getUri))
      assert(
        sourceUris.exists(_.endsWith("Lib.scala")),
        s"Expected Lib.scala in sources, got: ${sourceUris.mkString(", ")}",
      )
    }
  }

  test("aspect-scalac-options") {
    cleanWorkspace()
    for {
      _ <- initialize(
        BazelNativeBuildLayout(workspaceLayout, "2.13.12", bazelVersion)
      )
      wbt <- bsp.workspaceBuildTargets()
      lib = findTarget(wbt.getTargets, "mylib")
      scalacResult <- bsp.buildTargetScalacOptions(
        new ScalacOptionsParams(List(lib.getId).asJava)
      )
      items = scalacResult.getItems.asScala.toList
    } yield {
      assert(items.nonEmpty, "Expected scalac options items")
      val item = items.head
      val cp = item.getClasspath.asScala
      assert(
        cp.exists(u =>
          u.contains("scala-library") || u.contains("scala3-library")
        ),
        s"Expected scala-library in classpath, got: ${cp.take(5).mkString(", ")}...",
      )
      assert(
        item.getClassDirectory.nonEmpty,
        "Expected non-empty class directory",
      )
    }
  }

  test("aspect-javac-options") {
    cleanWorkspace()
    for {
      _ <- initialize(
        BazelNativeBuildLayout(workspaceLayout, "2.13.12", bazelVersion)
      )
      wbt <- bsp.workspaceBuildTargets()
      lib = findTarget(wbt.getTargets, "mylib")
      javacResult <- bsp.buildTargetJavacOptions(
        new JavacOptionsParams(List(lib.getId).asJava)
      )
      items = javacResult.getItems.asScala.toList
    } yield {
      assert(items.nonEmpty, "Expected javac options items")
      val item = items.head
      assert(
        item.getClassDirectory.nonEmpty,
        "Expected non-empty class directory for javac",
      )
    }
  }

  test("aspect-dependency-sources") {
    cleanWorkspace()
    for {
      _ <- initialize(
        BazelNativeBuildLayout(workspaceLayout, "2.13.12", bazelVersion)
      )
      wbt <- bsp.workspaceBuildTargets()
      app = findTarget(wbt.getTargets, "myapp")
      depSrcResult <- bsp.buildTargetDependencySources(
        new DependencySourcesParams(List(app.getId).asJava)
      )
      items = depSrcResult.getItems.asScala.toList
    } yield {
      assert(items.nonEmpty, "Expected dependency sources items for myapp")
    }
  }

  test("aspect-dependency-modules") {
    cleanWorkspace()
    for {
      _ <- initialize(
        BazelNativeBuildLayout(workspaceLayout, "2.13.12", bazelVersion)
      )
      wbt <- bsp.workspaceBuildTargets()
      lib = findTarget(wbt.getTargets, "mylib")
      depModResult <- bsp.buildTargetDependencyModules(
        new DependencyModulesParams(List(lib.getId).asJava)
      )
      items = depModResult.getItems.asScala.toList
    } yield {
      assert(items.nonEmpty, "Expected dependency module items")
      val modules = items.flatMap(_.getModules.asScala)
      assert(
        modules.nonEmpty,
        "Expected at least one dependency module for mylib",
      )
    }
  }

  test("aspect-inverse-sources") {
    cleanWorkspace()
    for {
      _ <- initialize(
        BazelNativeBuildLayout(workspaceLayout, "2.13.12", bazelVersion)
      )
      _ <- bsp.workspaceBuildTargets()
      libSrc = workspace.resolve("Lib.scala")
      inverseResult <- bsp.buildTargetInverseSources(
        new InverseSourcesParams(
          new TextDocumentIdentifier(libSrc.toURI.toString)
        )
      )
      targets = inverseResult.getTargets.asScala.toList
    } yield {
      assert(
        targets.nonEmpty,
        "Expected at least one target for Lib.scala",
      )
      assert(
        targets.exists(_.getUri.contains("mylib")),
        s"Expected mylib in inverse-source targets, got: ${targets.map(_.getUri).mkString(", ")}",
      )
    }
  }

  test("aspect-references") {
    cleanWorkspace()
    for {
      _ <- initialize(
        BazelNativeBuildLayout(workspaceLayout, "2.13.12", bazelVersion)
      )
      _ <- server.didOpen("Lib.scala")
      _ <- server.didOpen("Main.scala")
      _ <- server.didSave("Main.scala")
      references <- server.references("Lib.scala", "greet")
    } yield {
      assert(
        references.contains("Lib.scala"),
        s"Expected reference in Lib.scala",
      )
    }
  }

  // ---------------------------------------------------------------
  // Bazel 9 + bzlmod (MODULE.bazel) without explicit rules_java dep
  // ---------------------------------------------------------------

  val bazel9Version = "9.0.0"

  /**
   * MODULE.bazel layout matching the user's real project:
   * - No rules_java direct dep (JavaInfo must be loaded explicitly)
   * - register_toolchains for semanticdb but NO toolchain targets in BUILD
   *   (Metals must auto-create them)
   */
  private def bzlmodLayout(scalaVersion: String): String =
    s"""|/.bazelversion
        |$bazel9Version
        |/MODULE.bazel
        |bazel_dep(name = "rules_scala", version = "7.2.2")
        |
        |register_toolchains("//:semanticdb_toolchain")
        |
        |scala_config = use_extension(
        |    "@rules_scala//scala/extensions:config.bzl",
        |    "scala_config",
        |)
        |scala_config.settings(scala_version = "$scalaVersion")
        |
        |scala_deps = use_extension(
        |    "@rules_scala//scala/extensions:deps.bzl",
        |    "scala_deps",
        |)
        |scala_deps.settings(
        |    fetch_sources = True,
        |)
        |scala_deps.scala()
        |
        |/BUILD
        |load("@rules_scala//scala:scala.bzl", "scala_binary", "scala_library")
        |
        |scala_library(
        |    name = "mylib",
        |    srcs = ["Lib.scala"],
        |)
        |
        |scala_binary(
        |    name = "myapp",
        |    srcs = ["Main.scala"],
        |    main_class = "myapp.Main",
        |    deps = [":mylib"],
        |)
        |
        |/Lib.scala
        |package mylib
        |
        |class Lib {
        |  def greet: String = "hello"
        |}
        |
        |/Main.scala
        |package myapp
        |
        |import mylib.Lib
        |
        |object Main {
        |  def main(args: Array[String]): Unit = {
        |    println(new Lib().greet)
        |  }
        |}
        |""".stripMargin

  test("aspect-bzlmod-bazel9-build-targets") {
    cleanWorkspace()
    for {
      _ <- initialize(bzlmodLayout("2.13.18"))
      result <- bsp.workspaceBuildTargets()
      targets = result.getTargets.asScala.toList
    } yield {
      assert(
        targets.size >= 2,
        s"Expected >= 2 targets, got ${targets.size}: " +
          targets.map(_.getDisplayName).mkString(", "),
      )

      val lib = findTarget(result.getTargets, "mylib")
      assert(
        lib.getCapabilities.getCanCompile,
        "mylib should be compilable",
      )

      val app = findTarget(result.getTargets, "myapp")
      assert(
        app.getCapabilities.getCanRun,
        "myapp (binary) should be runnable",
      )

      assert(
        lib.getDataKind == BuildTargetDataKind.SCALA,
        s"Expected SCALA data kind, got ${lib.getDataKind}",
      )
    }
  }

  test("aspect-bzlmod-bazel9-scalac-options") {
    cleanWorkspace()
    for {
      _ <- initialize(bzlmodLayout("2.13.18"))
      wbt <- bsp.workspaceBuildTargets()
      lib = findTarget(wbt.getTargets, "mylib")
      scalacResult <- bsp.buildTargetScalacOptions(
        new ScalacOptionsParams(List(lib.getId).asJava)
      )
      items = scalacResult.getItems.asScala.toList
    } yield {
      assert(items.nonEmpty, "Expected scalac options items")
      val item = items.head
      val cp = item.getClasspath.asScala
      assert(
        cp.exists(u =>
          u.contains("scala-library") || u.contains("scala3-library")
        ),
        s"Expected scala-library in classpath, got: " +
          cp.take(5).mkString(", ") + "...",
      )
    }
  }
}
