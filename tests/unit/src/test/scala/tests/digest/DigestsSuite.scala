package tests
package digest
import scala.meta.internal.builds.Digest
import scala.meta.internal.builds.Digest.Status._
import scala.meta.internal.builds.Digests

object DigestsSuite extends BaseTablesSuite {
  def digests: Digests = tables.digests
  test("basic") {
    assertDiffEqual(digests.setStatus("a", Requested), 1)
    assertDiffEqual(
      digests.last().get,
      Digest("a", Requested, time.currentMillis())
    )
    time.elapseSeconds(1)
    assertDiffEqual(digests.getStatus("a").get, Requested)
    assertDiffEqual(digests.setStatus("a", Installed), 1)
    assertDiffEqual(
      digests.last().get,
      Digest("a", Installed, time.currentMillis())
    )
    time.elapseSeconds(1)
    assertDiffEqual(digests.getStatus("a").get, Installed)
  }
}
