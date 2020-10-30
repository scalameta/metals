package tests

import scala.meta.internal.metals.ChosenBuildServers

class ChosenBuildServerSuite extends BaseTablesSuite {
  def buildServers: ChosenBuildServers = tables.buildServers
  test("basic") {
    assert(buildServers.selectedServer("a").isEmpty)
    assertDiffEqual(buildServers.chooseServer("a", "bill"), 1)
    assertDiffEqual(
      buildServers.selectedServer("a").get,
      "bill"
    )
  }
  test("explicit") {
    assert(buildServers.selectedServer("EXPLICIT").isEmpty)
    assertDiffEqual(buildServers.chooseServer("bill"), 1)
    assertDiffEqual(
      buildServers.selectedServer().get,
      "bill"
    )
    assertDiffEqual(
      buildServers.selectedServer("EXPLICIT").get,
      "bill"
    )
  }
  test("reset") {
    assert(buildServers.selectedServer("EXPLICIT").isEmpty)
    assertDiffEqual(buildServers.chooseServer("bill"), 1)
    assertDiffEqual(
      buildServers.selectedServer().get,
      "bill"
    )
    buildServers.reset()
    assertDiffEqual(
      buildServers.selectedServer(),
      None
    )
  }
}
