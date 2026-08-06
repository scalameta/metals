package tests.bazel

import scala.meta.internal.metals.mbt.importer.BazelLabels

import tests.BaseSuite

class BazelLabelsSuite extends BaseSuite {

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

  test("rejects-non-file-and-external-labels") {
    assertEquals(BazelLabels.fileLabelToWorkspaceRelativePath("//pkg:"), None)
    assertEquals(
      BazelLabels.fileLabelToWorkspaceRelativePath("@maven//:artifact"),
      None,
    )
    assertEquals(BazelLabels.fileLabelToWorkspaceRelativePath("//pkg"), None)
  }
}
