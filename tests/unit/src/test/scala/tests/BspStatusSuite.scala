package tests

import java.nio.file.Paths

import scala.meta.internal.metals.BspStatus
import scala.meta.internal.metals.clients.language.MetalsStatusParams
import scala.meta.internal.metals.clients.language.NoopLanguageClient
import scala.meta.io.AbsolutePath

class BspStatusSuite extends BaseSuite {

  test("basic") {
    val client = new StatusClient
    val bspStatus = new BspStatus(client, isBspStatusProvider = true)
    val folder1 = AbsolutePath(Paths.get(".")).resolve("folder1")
    val folder2 = AbsolutePath(Paths.get(".")).resolve("folder2")
    bspStatus.status(folder1, new MetalsStatusParams("some text"))
    assertEquals(client.status, "some text")
    bspStatus.status(folder2, new MetalsStatusParams("other text"))
    assertEquals(client.status, "other text")
    bspStatus.focus(folder1)
    assertEquals(client.status, "some text")
    bspStatus.status(folder2, new MetalsStatusParams("some other other text"))
    assertEquals(client.status, "some text")
    bspStatus.focus(folder1)
    assertEquals(client.status, "some text")
    bspStatus.focus(folder2)
    assertEquals(client.status, "some other other text")
  }

  test("no-bsp-status") {
    val client = new StatusClient
    val bspStatus = new BspStatus(client, isBspStatusProvider = false)
    val folder1 = AbsolutePath(Paths.get(".")).resolve("folder1")
    val folder2 = AbsolutePath(Paths.get(".")).resolve("folder2")
    bspStatus.status(folder1, new MetalsStatusParams("some text"))
    assertEquals(client.status, "some text")
    bspStatus.status(folder2, new MetalsStatusParams("other text"))
    assertEquals(client.status, "other text")
    bspStatus.focus(folder1)
    assertEquals(client.status, "other text")
    bspStatus.status(folder2, new MetalsStatusParams("some other other text"))
    assertEquals(client.status, "some other other text")
    bspStatus.focus(folder1)
    assertEquals(client.status, "some other other text")
  }

}

class StatusClient extends NoopLanguageClient {

  var status: String = ""

  override def metalsStatus(params: MetalsStatusParams): Unit =
    status = params.text
}
