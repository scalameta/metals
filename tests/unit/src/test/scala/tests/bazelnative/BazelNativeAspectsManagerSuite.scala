package tests.bazelnative

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import scala.meta.internal.builds.bazelnative.BazelNativeAspectsManager
import scala.meta.internal.metals.RecursivelyDelete
import scala.meta.io.AbsolutePath

import munit.FunSuite

class BazelNativeAspectsManagerSuite extends FunSuite {

  private def withTempWorkspace(fn: AbsolutePath => Unit): Unit = {
    val tmp = AbsolutePath(Files.createTempDirectory("bazel-aspects-test"))
    try fn(tmp)
    finally RecursivelyDelete(tmp)
  }

  test("aspect .bzl file is written to .metals/bazel-native-bsp/aspects/") {
    withTempWorkspace { workspace =>
      val manager = new BazelNativeAspectsManager(workspace)
      manager.materialize(Some("rules_scala"))

      val bzlFile = workspace
        .resolve(".metals")
        .resolve("bazel-native-bsp")
        .resolve("aspects")
        .resolve("bsp_target_info.bzl")
      assert(bzlFile.isFile, s"expected $bzlFile to exist")
    }
  }

  test("BUILD file is written alongside .bzl") {
    withTempWorkspace { workspace =>
      val manager = new BazelNativeAspectsManager(workspace)
      manager.materialize(Some("rules_scala"))

      val buildFile =
        workspace
          .resolve(".metals")
          .resolve("bazel-native-bsp")
          .resolve("aspects")
          .resolve("BUILD")
      assert(buildFile.isFile, s"expected $buildFile to exist")
    }
  }

  test("generated .bzl contains bsp_target_info_aspect") {
    withTempWorkspace { workspace =>
      val manager = new BazelNativeAspectsManager(workspace)
      manager.materialize(Some("rules_scala"))

      val bzlFile = workspace
        .resolve(".metals")
        .resolve("bazel-native-bsp")
        .resolve("aspects")
        .resolve("bsp_target_info.bzl")
      val content =
        new String(Files.readAllBytes(bzlFile.toNIO), StandardCharsets.UTF_8)
      assert(
        content.contains("bsp_target_info_aspect"),
        s"expected .bzl to define bsp_target_info_aspect",
      )
    }
  }

  test("--aspects flag points to correct path") {
    withTempWorkspace { workspace =>
      val manager = new BazelNativeAspectsManager(workspace)
      assertEquals(
        manager.aspectsFlag,
        "//.metals/bazel-native-bsp/aspects:bsp_target_info.bzl%bsp_target_info_aspect",
      )
    }
  }

  test("--output_groups sync flag is bsp-target-info") {
    withTempWorkspace { workspace =>
      val manager = new BazelNativeAspectsManager(workspace)
      assertEquals(manager.outputGroupsSyncFlag, "bsp-target-info")
    }
  }

  test("materialization is idempotent") {
    withTempWorkspace { workspace =>
      val manager = new BazelNativeAspectsManager(workspace)
      manager.materialize(Some("rules_scala"))
      manager.materialize(Some("rules_scala")) // no error
    }
  }
}
