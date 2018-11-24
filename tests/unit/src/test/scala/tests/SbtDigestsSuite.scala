package tests

import scala.meta.internal.metals.SbtDigest
import scala.meta.internal.metals.SbtDigest.Status._
import scala.meta.internal.metals.SbtDigests

object SbtDigestsSuite extends BaseTablesSuite {
  def digests: SbtDigests = tables.sbtDigests
  test("basic") {
    assertEquals(digests.setStatus("a", Requested), 1)
    assertEquals(
      digests.last().get,
      SbtDigest("a", Requested, time.millis())
    )
    time.elapseSeconds(1)
    assertEquals(digests.getStatus("a").get, Requested)
    assertEquals(digests.setStatus("a", Installed), 1)
    assertEquals(
      digests.last().get,
      SbtDigest("a", Installed, time.millis())
    )
    time.elapseSeconds(1)
    assertEquals(digests.getStatus("a").get, Installed)
  }
}
