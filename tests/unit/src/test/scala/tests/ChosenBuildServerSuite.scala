package tests

import scala.meta.internal.metals.ChosenBuildServers

class ChosenBuildServerSuite extends BaseTablesSuite {
  def buildServers: ChosenBuildServers = tables.buildServers

  test("basic") {
    assert(buildServers.selectedServer().isEmpty)
    assertDiffEqual(buildServers.chooseServer("bill"), 1)
    assertDiffEqual(
      buildServers.selectedServer().get,
      "bill",
    )
  }
  test("reset") {
    assert(buildServers.selectedServer().isEmpty)
    assertDiffEqual(buildServers.chooseServer("bill"), 1)
    assertDiffEqual(
      buildServers.selectedServer().get,
      "bill",
    )
    buildServers.reset()
    assertDiffEqual(
      buildServers.selectedServer(),
      None,
    )
  }
}
