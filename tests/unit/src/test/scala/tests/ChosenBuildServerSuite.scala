package tests

import scala.meta.internal.metals.ChosenBuildServers

object ChosenBuildServerSuite extends BaseTablesSuite {
  def buildServers: ChosenBuildServers = tables.buildServers
  test("basic") {
    assert(buildServers.selectedServer("a").isEmpty)
    assertEquals(buildServers.chooseServer("a", "bill"), 1)
    assertEquals(
      buildServers.selectedServer("a").get,
      "bill"
    )
  }
}
