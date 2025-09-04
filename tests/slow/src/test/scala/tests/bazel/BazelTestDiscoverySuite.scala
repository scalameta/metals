package tests.bazel

import scala.jdk.CollectionConverters._

import scala.meta.internal.builds.BazelBuildTool
import scala.meta.internal.builds.BazelDigest
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.io.AbsolutePath

import tests.BaseImportSuite
import tests.BazelModuleLayout
import tests.BazelServerInitializer

class BazelTestDiscoverySuite
    extends BaseImportSuite("bazel-test-discovery", BazelServerInitializer) {

  val bazelVersion = "8.2.1"

  val buildTool: BazelBuildTool = BazelBuildTool(() => userConfig, workspace)

  override def currentDigest(
      workspace: AbsolutePath
  ): Option[String] = BazelDigest.current(workspace)

  test("simple-scalatest-discovery") {
    cleanWorkspace()
    val testLayout =
      s"""|/BUILD
          |load("@rules_scala//scala:scala_toolchain.bzl", "scala_toolchain")
          |load("@rules_scala//scala:scala.bzl", "scala_test")
          |
          |scala_toolchain(
          |    name = "custom_scala_toolchain_impl",
          |    enable_semanticdb = True,
          |    strict_deps_mode = "error",
          |    unused_dependency_checker_mode = "warn",
          |)
          |
          |toolchain(
          |    name = "custom_scala_toolchain",
          |    toolchain = ":custom_scala_toolchain_impl",
          |    toolchain_type = "@rules_scala//scala:toolchain_type",
          |    visibility = ["//visibility:public"],
          |)
          |
          |scala_test(
          |    name = "simple_test",
          |    srcs = ["SimpleTest.scala"],
          |)
          |
          |/SimpleTest.scala
          |import org.scalatest.funsuite.AnyFunSuite
          |
          |class SimpleTest extends AnyFunSuite {
          |  test("simple test case") {
          |    assert(1 + 1 == 2)
          |  }
          |}
          |
          |""".stripMargin

    for {
      _ <- initialize(
        BazelModuleLayout(
          testLayout,
          V.scala3,
          bazelVersion,
        )
      )
      _ <- server.didOpen("SimpleTest.scala")
      _ <- server.didSave("SimpleTest.scala")

      // Wait for compilation and indexing to complete
      _ <- server.waitFor(java.util.concurrent.TimeUnit.SECONDS.toMillis(10))

      // Discover test suites
      testSuites <- server.discoverTestSuites(List("SimpleTest.scala"))

    } yield {
      // Verify that we found the test class
      val testEvents = testSuites.flatMap(_.events.asScala.toList)
      assert(
        testEvents.nonEmpty,
        s"Expected to find test events, but got empty list. TestSuites: $testSuites",
      )

      // Check that SimpleTest was discovered
      val testClasses = testEvents.collect {
        case event if event.toString.contains("SimpleTest") => "SimpleTest"
      }
      assert(
        testClasses.nonEmpty,
        s"Expected to find 'SimpleTest' in discovered classes: ${testEvents.mkString(", ")}",
      )
    }
  }

  test("scalatest-with-multiple-tests") {
    cleanWorkspace()
    val testLayout =
      s"""|/BUILD
          |load("@rules_scala//scala:scala_toolchain.bzl", "scala_toolchain")
          |load("@rules_scala//scala:scala.bzl", "scala_test")
          |
          |scala_toolchain(
          |    name = "custom_scala_toolchain_impl",
          |    enable_semanticdb = True,
          |    strict_deps_mode = "error",
          |    unused_dependency_checker_mode = "warn",
          |)
          |
          |toolchain(
          |    name = "custom_scala_toolchain",
          |    toolchain = ":custom_scala_toolchain_impl",
          |    toolchain_type = "@rules_scala//scala:toolchain_type",
          |    visibility = ["//visibility:public"],
          |)
          |
          |scala_test(
          |    name = "multi_test",
          |    srcs = ["MultiTest.scala"],
          |)
          |
          |/MultiTest.scala
          |import org.scalatest.funsuite.AnyFunSuite
          |
          |class MultiTest extends AnyFunSuite {
          |  test("first test") {
          |    assert(1 + 1 == 2)
          |  }
          |
          |  test("second test") {
          |    assert(2 * 2 == 4)
          |  }
          |
          |  test("third test") {
          |    assert(3 * 3 == 9)
          |  }
          |}
          |
          |""".stripMargin

    for {
      _ <- initialize(
        BazelModuleLayout(
          testLayout,
          V.bazelScalaVersion,
          bazelVersion,
        )
      )
      _ <- server.didOpen("MultiTest.scala")
      _ <- server.didSave("MultiTest.scala")

      // Wait for compilation and indexing to complete
      _ <- server.waitFor(java.util.concurrent.TimeUnit.SECONDS.toMillis(10))

      // Discover test suites
      testSuites <- server.discoverTestSuites(List("MultiTest.scala"))

    } yield {
      // Verify that we found the test class
      val testEvents = testSuites.flatMap(_.events.asScala.toList)
      assert(
        testEvents.nonEmpty,
        s"Expected to find test events, but got empty list",
      )

      // Check that MultiTest was discovered
      val testClasses = testEvents.collect {
        case event if event.toString.contains("MultiTest") => "MultiTest"
      }
      assert(
        testClasses.nonEmpty,
        s"Expected to find 'MultiTest' in discovered classes: ${testEvents.mkString(", ")}",
      )
    }
  }

  test("no-test-classes") {
    cleanWorkspace()
    val nonTestLayout =
      s"""|/BUILD
          |load("@rules_scala//scala:scala_toolchain.bzl", "scala_toolchain")
          |load("@rules_scala//scala:scala.bzl", "scala_library")
          |
          |scala_toolchain(
          |    name = "custom_scala_toolchain_impl",
          |    enable_semanticdb = True,
          |    strict_deps_mode = "error",
          |    unused_dependency_checker_mode = "warn",
          |)
          |
          |toolchain(
          |    name = "custom_scala_toolchain",
          |    toolchain = ":custom_scala_toolchain_impl",
          |    toolchain_type = "@rules_scala//scala:toolchain_type",
          |    visibility = ["//visibility:public"],
          |)
          |
          |scala_library(
          |    name = "regular_lib",
          |    srcs = ["RegularClass.scala"],
          |)
          |
          |/RegularClass.scala
          |class RegularClass {
          |  def regularMethod(): String = "Hello World"
          |}
          |
          |""".stripMargin

    for {
      _ <- initialize(
        BazelModuleLayout(
          nonTestLayout,
          V.bazelScalaVersion,
          bazelVersion,
        )
      )
      _ <- server.didOpen("RegularClass.scala")
      _ <- server.didSave("RegularClass.scala")

      // Wait for compilation and indexing to complete
      _ <- server.waitFor(java.util.concurrent.TimeUnit.SECONDS.toMillis(10))

      // Discover test suites - should return empty results
      testSuites <- server.discoverTestSuites(List("RegularClass.scala"))

    } yield {
      // Verify that we found no test events for a regular class
      val testEvents = testSuites.flatMap(_.events.asScala.toList)
      assert(
        testEvents.isEmpty,
        s"Expected no test events for regular class, but found: ${testEvents.mkString(", ")}",
      )
    }
  }
}
