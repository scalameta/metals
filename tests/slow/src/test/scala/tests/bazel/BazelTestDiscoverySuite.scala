package tests.bazel

import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters._

import scala.meta.internal.builds.BazelBuildTool
import scala.meta.internal.builds.BazelDigest
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
}
