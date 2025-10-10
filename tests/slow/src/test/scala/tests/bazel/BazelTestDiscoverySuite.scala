package tests.bazel

import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters._

import scala.meta.internal.builds.BazelBuildTool
import scala.meta.internal.builds.BazelDigest
import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.metals.DebugDiscoveryParams
import scala.meta.internal.metals.JsonParser._
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.io.AbsolutePath

import tests.BaseImportSuite
import tests.BazelModuleLayout
import tests.BazelServerInitializer

class BazelTestDiscoverySuite
    extends BaseImportSuite("bazel-test-discovery", BazelServerInitializer) {

  val bazelVersion = "8.2.1"

  val buildTool: BazelBuildTool = BazelBuildTool(() => userConfig, workspace)

  def buildFileWithToolchain(): String =
    s"""|/BUILD
        |load("@rules_scala//scala:scala_toolchain.bzl", "scala_toolchain")
        |load("@rules_scala//scala:scala.bzl", "scala_test", "scala_junit_test")
        |
        |scala_toolchain(
        |    name = "semanticdb_toolchain_impl",
        |    enable_semanticdb = True,
        |    strict_deps_mode = "error",
        |    unused_dependency_checker_mode = "warn",
        |)
        |
        |toolchain(
        |    name = "semanticdb_toolchain",
        |    toolchain = ":semanticdb_toolchain_impl",
        |    toolchain_type = "@rules_scala//scala:toolchain_type",
        |    visibility = ["//visibility:public"],
        |)
        |
        |""".stripMargin

  override def currentDigest(
      workspace: AbsolutePath
  ): Option[String] = BazelDigest.current(workspace)

  override def afterEach(context: AfterEach): Unit = {
    try {
      // Shutdown Bazel server after each test
      ShellRunner.runSync(
        List("bazel", "shutdown"),
        workspace,
        redirectErrorOutput = false,
      ) match {
        case Some(output) =>
          scribe.info(s"Bazel shutdown completed: $output")
        case None =>
          scribe.warn("Bazel shutdown command failed or produced no output")
      }
    } catch {
      case e: Exception =>
        scribe.warn(s"Failed to shutdown Bazel server: ${e.getMessage}")
    } finally {
      super.afterEach(context)
    }
  }

  test("simple-scalatest-discovery") {
    cleanWorkspace()
    val testLayout =
      s"""|${buildFileWithToolchain()}
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
          enableToolChainRegistration = true,
        )
      )
      _ <- server.didOpen("SimpleTest.scala")
      _ <- server.didSave("SimpleTest.scala")

      _ <- server.waitFor(java.util.concurrent.TimeUnit.SECONDS.toMillis(10))

      testSuites <- server.discoverTestSuites(List("SimpleTest.scala"))

    } yield {
      val testEvents = testSuites.flatMap(_.events.asScala.toList)
      assert(
        testEvents.nonEmpty,
        s"Expected to find test events, but got empty list. TestSuites: $testSuites",
      )

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
      s"""|${buildFileWithToolchain()}
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
          V.scala3,
          bazelVersion,
          enableToolChainRegistration = true,
        )
      )

      _ <- server.didOpen("MultiTest.scala")
      _ <- server.didSave("MultiTest.scala")

      _ <- server.waitFor(java.util.concurrent.TimeUnit.SECONDS.toMillis(10))

      testSuites <- server.discoverTestSuites(List("MultiTest.scala"))

    } yield {
      val testEvents = testSuites.flatMap(_.events.asScala.toList)
      assert(
        testEvents.nonEmpty,
        s"Expected to find test events, but got empty list",
      )

      val testClasses = testEvents.collect {
        case event if event.toString.contains("MultiTest") => "MultiTest"
      }
      assert(
        testClasses.nonEmpty,
        s"Expected to find 'MultiTest' in discovered classes: ${testEvents.mkString(", ")}",
      )
    }
  }

  test("junit-test-discovery") {
    cleanWorkspace()
    val testLayout =
      s"""|${buildFileWithToolchain()}
          |scala_junit_test(
          |    name = "junit_test",
          |    srcs = ["JUnitTest.scala"],
          |    suffixes = ["Test"],
          |    deps = ["@maven//:junit_junit"],
          |)
          |/JUnitTest.scala
          |import org.junit.Test
          |import org.junit.Assert._
          |
          |class JUnitTest {
          |
          |  @Test
          |  def testSimpleAssertion(): Unit = {
          |    assertEquals(2, 1 + 1 + 3)
          |  }
          |
          |  @Test
          |  def testAnotherAssertion(): Unit = {
          |    assertTrue("This should be true", true)
          |  }
          |}
          |""".stripMargin

    for {
      _ <- initialize(
        BazelModuleLayout(
          testLayout,
          V.scala3,
          bazelVersion,
          enableToolChainRegistration = true,
        )
      )
      _ <- server.didOpen("JUnitTest.scala")
      _ <- server.didSave("JUnitTest.scala")

      _ <- server.waitFor(java.util.concurrent.TimeUnit.SECONDS.toMillis(15))

      testSuites <- server.discoverTestSuites(List("JUnitTest.scala"))

    } yield {
      val testEvents = testSuites.flatMap(_.events.asScala.toList)
      assert(
        testEvents.nonEmpty,
        s"Expected to find test events, but got empty list. TestSuites: $testSuites",
      )

      val testClasses = testEvents.collect {
        case event if event.toString.contains("JUnitTest") => "JUnitTest"
      }
      assert(
        testClasses.nonEmpty,
        s"Expected to find 'JUnitTest' in discovered classes: ${testEvents.mkString(", ")}",
      )
    }
  }

  test("scalatest-debug-mode") {
    cleanWorkspace()
    val testLayout =
      s"""|${buildFileWithToolchain()}
          |scala_test(
          |    name = "debug_test",
          |    srcs = ["DebugTest.scala"],
          |)
          |
          |/DebugTest.scala
          |import org.scalatest.funsuite.AnyFunSuite
          |
          |class DebugTest extends AnyFunSuite {
          |  test("debug test case") {
          |    val result = 2 + 2
          |    assert(result == 4, s"Expected 4, got $$result")
          |  }
          |
          |  test("another debug test") {
          |    val message = "Hello from Bazel debug test"
          |    assert(message.nonEmpty)
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
          enableToolChainRegistration = true,
        )
      )
      _ <- server.didOpen("DebugTest.scala")
      _ <- server.didSave("DebugTest.scala")

      _ <- server.waitFor(TimeUnit.SECONDS.toMillis(15))

      debugger <- server.startDebuggingUnresolved(
        new DebugDiscoveryParams(
          server.toPath("DebugTest.scala").toURI.toString,
          "testFile",
        ).toJson
      )
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput

    } yield {
      assert(
        output.contains("All tests in DebugTest passed") || output.contains(
          "test completed"
        ),
        s"Expected test execution success message in debug output, but got: $output",
      )
    }
  }
}
