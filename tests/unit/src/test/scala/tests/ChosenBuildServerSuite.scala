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
}
