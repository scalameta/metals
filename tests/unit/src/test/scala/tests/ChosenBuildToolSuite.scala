package tests

import scala.meta.internal.metals.ChosenBuildTool

class ChosenBuildToolSuite extends BaseTablesSuite {
  def buildTool: ChosenBuildTool = tables.buildTool
  test("basic") {
    assert(buildTool.selectedBuildTool().isEmpty)
    assertDiffEqual(buildTool.chooseBuildTool("sbt"), 1)
    assertDiffEqual(
      buildTool.selectedBuildTool().get,
      "sbt"
    )
  }
}
