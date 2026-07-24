package tests.bazel

import java.nio.file.Files

import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

import scala.meta.internal.builds.BazelBuildTool
import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.metals.EmptyWorkDoneProgress
import scala.meta.internal.metals.RecursivelyDelete
import scala.meta.internal.metals.Time
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.mbt.importer.BazelQuery
import scala.meta.io.AbsolutePath

import tests.BaseSuite

// Pins the whole query pipeline ([[BazelQuery.runProtoDump]]) against real
// Bazel 5.0.0 output — the oldest supported version and the reason for
// `streamed_proto` (5.0.0 rejects `streamed_jsonproto`). No other suite runs
// 5.0.0; the version is pinned via `.bazelversion` (bazelisk).
class BazelStreamedProtoBazel5QuerySuite extends BaseSuite {

  private implicit val ec: ExecutionContext = ExecutionContext.global

  private val bazelVersion = BazelBuildTool.minBazelVersion

  private lazy val workspace: AbsolutePath =
    AbsolutePath(Files.createTempDirectory("metals-bazel5-query"))

  private def write(name: String, content: String): Unit =
    Files.writeString(workspace.resolve(name).toNIO, content)

  override def beforeAll(): Unit = {
    write(".bazelversion", s"$bazelVersion\n")
    // Bazel 5 predates bzlmod; an empty WORKSPACE file marks the root.
    write("WORKSPACE", "")
    write("A.java", "class A {}\n")
    write("B.java", "class B {}\n")
    write(
      "BUILD",
      """|config_setting(
         |    name = "flagged",
         |    values = {"define": "flagged=1"},
         |)
         |
         |java_library(
         |    name = "lib",
         |    srcs = select({
         |        ":flagged": ["A.java"],
         |        "//conditions:default": ["B.java"],
         |    }),
         |    javacopts = ["-Xlint:all"],
         |)
         |""".stripMargin,
    )
  }

  override def afterAll(): Unit = {
    // Best-effort, like BaseBazelServerSuite.cleanBazelServer: a failed or
    // slow shutdown must not fail the suite after its assertions have run.
    try {
      ShellRunner.runSync(
        List("bazel", "shutdown"),
        workspace,
        redirectErrorOutput = false,
      )
    } catch {
      case NonFatal(e) =>
        scribe.warn(s"Failed to shut down the Bazel server: ${e.getMessage}")
    } finally {
      RecursivelyDelete(workspace)
    }
  }

  test("bazel-5.0.0-streamed-proto-query") {
    val shellRunner =
      new ShellRunner(
        Time.system,
        EmptyWorkDoneProgress,
        () => UserConfiguration(),
      )
    val env = BazelQuery.Env(workspace, shellRunner, javaHome = None)
    // The production fullInformationQuery wraps its targets in deps(), which
    // on this workspace would drag in the whole @bazel_tools closure; querying
    // //:all keeps the dump scoped to the two declared targets while still
    // running in the production output mode with its proto flags.
    val query =
      BazelQuery("//:all", BazelQuery.OutputMode.StreamedProto)
    query.runProtoDump(env).map { dump =>
      assertEquals(
        dump.ruleClassesByTarget,
        Map("//:flagged" -> "config_setting", "//:lib" -> "java_library"),
      )
      // The select() branches survive --proto:flatten_selects=false; neither
      // branch is guarded by a scala_version config_setting, so both sources
      // are treated as unconditionally compiled.
      val srcs = dump.srcsByTarget("//:lib")
      assertEquals(srcs.unconditional, Set("//:A.java", "//:B.java"))
      assertEquals(srcs.byVersion, Map.empty[String, Set[String]])

      assertEquals(dump.getStrings("javacopts")("//:lib"), List("-Xlint:all"))
      assertEquals(
        dump.srcLabelsByTarget("//:lib"),
        List("//:A.java", "//:B.java"),
      )
      assertEquals(
        dump.ruleOutputsByTarget("//:lib"),
        List("//:liblib.jar", "//:liblib-src.jar"),
      )
    }
  }
}
