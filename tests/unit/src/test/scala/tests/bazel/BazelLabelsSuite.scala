package tests.bazel

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import scala.meta.internal.metals.mbt.MbtWorkspaceSymbolProvider
import scala.meta.internal.metals.mbt.importer.BazelLabels
import scala.meta.internal.metals.mbt.importer.BazelMbtBuildSupport
import scala.meta.internal.metals.mbt.importer.BazelMbtNamespaceMode

import tests.BaseSuite
import tests.FileLayout

class BazelLabelsSuite extends BaseSuite {
  private implicit val ec: ExecutionContext = ExecutionContext.global

  test("file-label-to-workspace-relative-path") {
    assertEquals(
      BazelLabels.fileLabelToWorkspaceRelativePath("//path/to:File.scala"),
      Some("path/to/File.scala"),
    )
    assertEquals(
      BazelLabels.fileLabelToWorkspaceRelativePath("//pkg:sub/dir/File.scala"),
      Some("pkg/sub/dir/File.scala"),
    )
  }

  test("root-package-file-label-has-no-leading-slash") {
    assertEquals(
      BazelLabels.fileLabelToWorkspaceRelativePath("//:File.scala"),
      Some("File.scala"),
    )
  }

  test("glob-call-is-package-relative") {
    assertEquals(
      BazelLabels.srcGlob("""glob(["src/test/scala/**/*.scala"])"""),
      Some(
        BazelLabels.SrcGlob(
          includes = List("src/test/scala/**/*.scala"),
          excludes = Nil,
          packageRelative = true,
        )
      ),
    )
    assertEquals(
      BazelLabels.workspaceGlob(
        "pkg",
        "src/test/scala/**/*.scala",
        packageRelative = true,
      ),
      "pkg/src/test/scala/**/*.scala",
    )
  }

  test("glob-call-exclude") {
    assertEquals(
      BazelLabels.srcGlob(
        """glob(["src/test/scala/**/*.scala"], exclude = ["src/test/scala/**/*Ignore.scala"])"""
      ),
      Some(
        BazelLabels.SrcGlob(
          includes = List("src/test/scala/**/*.scala"),
          excludes = List("src/test/scala/**/*Ignore.scala"),
          packageRelative = true,
        )
      ),
    )
  }

  test("glob-label-is-workspace-relative") {
    assertEquals(
      BazelLabels.srcGlob("//pkg:src/test/scala/**/*.scala"),
      Some(
        BazelLabels.SrcGlob(
          includes = List("pkg/src/test/scala/**/*.scala"),
          excludes = Nil,
          packageRelative = false,
        )
      ),
    )
  }

  test("rejects-non-file-and-external-labels") {
    assertEquals(BazelLabels.fileLabelToWorkspaceRelativePath("//pkg:"), None)
    assertEquals(
      BazelLabels.fileLabelToWorkspaceRelativePath("@maven//:artifact"),
      None,
    )
    assertEquals(BazelLabels.fileLabelToWorkspaceRelativePath("//pkg"), None)
  }

  test("glob-srcs-discover-test-files") {
    val workspace = FileLayout.fromString(
      """|/pkg/src/test/scala/foo/FooTest.scala
         |package foo
         |class FooTest
         |/pkg/src/test/scala/bar/BarTest.scala
         |package bar
         |class BarTest
         |/pkg/src/test/scala/bar/IgnoredTest.scala
         |package bar
         |class IgnoredTest
         |/pkg/src/main/scala/Main.scala
         |package foo
         |class Main
         |""".stripMargin
    )
    val provider = new MbtWorkspaceSymbolProvider(workspace)
    val files = List(
      "pkg/src/test/scala/foo/FooTest.scala",
      "pkg/src/test/scala/bar/BarTest.scala",
      "pkg/src/test/scala/bar/IgnoredTest.scala",
      "pkg/src/main/scala/Main.scala",
    )
    files.foreach { relative =>
      Await.result(
        provider.onDidChange(workspace.resolve(relative)),
        30.seconds,
      )
    }

    val build = BazelMbtBuildSupport.fromDiscovery(
      granularity = BazelMbtNamespaceMode.BuildFile,
      targetLabels = List("//pkg:tests"),
      srcsByTarget = Map(
        "//pkg:tests" -> List(
          """glob(["src/test/scala/**/*.scala"], exclude = ["src/test/scala/**/IgnoredTest.scala"])"""
        )
      ),
      scalacOptionsByTarget = Map.empty,
      javacOptionsByTarget = Map.empty,
      directDepRules = Map.empty,
      externalDepsByTarget =
        Map("//pkg:tests" -> List("org.scalatest:scalatest_2.13:3.2.19")),
      runTargets = Set("//pkg:tests"),
      classDirectoriesByTarget = Map.empty,
      dependencyModules = Nil,
      scalaVersion = None,
      testTargets = Set("//pkg:tests"),
      mbtWorkspaceSymbolProvider = Some(provider),
    )

    val testClasses =
      build.getNamespaces.get("//pkg").getTestClasses.map { tc =>
        (tc.className, tc.sourcePath, tc.framework, tc.configuration)
      }
    assertEquals(
      testClasses,
      Seq(
        (
          "bar.BarTest",
          "pkg/src/test/scala/bar/BarTest.scala",
          "ScalaTest",
          "//pkg:tests",
        ),
        (
          "foo.FooTest",
          "pkg/src/test/scala/foo/FooTest.scala",
          "ScalaTest",
          "//pkg:tests",
        ),
      ),
    )
  }
}
