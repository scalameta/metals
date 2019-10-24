package tests
package digest
import scala.meta.internal.builds.Digest
import scala.meta.internal.builds.Digest.Status._
import scala.meta.internal.builds.Digests

object DigestsSuite extends BaseTablesSuite {
  def digests: Digests = tables.digests
  test("basic") {
    assertEquals(digests.setStatus("a", Requested), 1)
    assertEquals(
      digests.last().get,
      Digest("a", Requested, time.currentMillis())
    )
    time.elapseSeconds(1)
    assertEquals(digests.getStatus("a").get, Requested)
    assertEquals(digests.setStatus("a", Installed), 1)
    assertEquals(
      digests.last().get,
      Digest("a", Installed, time.currentMillis())
    )
    time.elapseSeconds(1)
    assertEquals(digests.getStatus("a").get, Installed)
  }
}
