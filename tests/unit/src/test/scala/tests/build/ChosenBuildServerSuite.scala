package tests.build

import scala.meta.internal.metals.ChosenBuildServers
import tests.BaseTablesSuite

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
