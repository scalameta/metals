package tests.bazelnative

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import scala.meta.internal.builds.BazelNativeBuildTool
import scala.meta.internal.builds.BuildTools
import scala.meta.internal.metals.RecursivelyDelete
import scala.meta.internal.metals.UserConfiguration
import scala.meta.io.AbsolutePath

import munit.FunSuite

class BazelNativeDiscoverySuite extends FunSuite {

  private def withTempWorkspace(fn: AbsolutePath => Unit): Unit = {
    val tmp = AbsolutePath(Files.createTempDirectory("bazel-native-test"))
    try fn(tmp)
    finally {
      RecursivelyDelete(tmp)
    }
  }

  private def makeBuildTools(workspace: AbsolutePath): BuildTools =
    new BuildTools(
      workspace,
      bspGlobalDirectories = Nil,
      () => UserConfiguration(),
      explicitChoiceMade = () => false,
      StandardCharsets.UTF_8,
    )

  test("detects bazel-native when WORKSPACE exists") {
    withTempWorkspace { workspace =>
      Files.write(
        workspace.resolve("WORKSPACE").toNIO,
        "# empty".getBytes(StandardCharsets.UTF_8),
      )

      val buildTools = makeBuildTools(workspace)
      assert(buildTools.isBazelNative, "expected isBazelNative to be true")
    }
  }

  test("detects bazel-native when MODULE.bazel exists") {
    withTempWorkspace { workspace =>
      Files.write(
        workspace.resolve("MODULE.bazel").toNIO,
        "# module".getBytes(StandardCharsets.UTF_8),
      )

      val buildTools = makeBuildTools(workspace)
      assert(buildTools.isBazelNative, "expected isBazelNative to be true")
    }
  }

  test("no bazel-native when no WORKSPACE or MODULE.bazel") {
    withTempWorkspace { workspace =>
      val buildTools = makeBuildTools(workspace)
      assert(!buildTools.isBazelNative, "expected isBazelNative to be false")
    }
  }

  test("BazelNativeBuildTool has correct name and bspName") {
    assertEquals(BazelNativeBuildTool.name, "bazel-native")
    assertEquals(BazelNativeBuildTool.bspName, "bazel-native")
  }

  test("all available build tools includes BazelNativeBuildTool") {
    withTempWorkspace { workspace =>
      Files.write(
        workspace.resolve("WORKSPACE").toNIO,
        "# empty".getBytes(StandardCharsets.UTF_8),
      )

      val buildTools = makeBuildTools(workspace)
      val all = buildTools.allAvailable
      val hasBazelNative = all.exists(_.isInstanceOf[BazelNativeBuildTool])
      assert(hasBazelNative, s"expected BazelNativeBuildTool in: $all")
    }
  }

  test("isBuildRelated identifies BUILD file") {
    withTempWorkspace { workspace =>
      Files.write(
        workspace.resolve("WORKSPACE").toNIO,
        "# empty".getBytes(StandardCharsets.UTF_8),
      )
      Files.write(
        workspace.resolve("BUILD").toNIO,
        "# build".getBytes(StandardCharsets.UTF_8),
      )

      val buildTools = makeBuildTools(workspace)
      val result = buildTools.isBuildRelated(workspace.resolve("BUILD"))
      assert(result.isDefined, "expected BUILD to be build-related")
    }
  }
}
