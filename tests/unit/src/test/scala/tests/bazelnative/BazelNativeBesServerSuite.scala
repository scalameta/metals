package tests.bazelnative

import java.net.Socket

import scala.meta.internal.builds.bazelnative.BazelNativeBepTranslator
import scala.meta.internal.builds.bazelnative.BazelNativeBesServer

import munit.FunSuite

class BazelNativeBesServerSuite extends FunSuite {

  private def mkTranslator(): BazelNativeBepTranslator = {
    val t = new BazelNativeBepTranslator()
    t.setClient(new BazelNativeMockClient())
    t
  }

  test("start binds to ephemeral port") {
    val server = new BazelNativeBesServer(mkTranslator())

    try {
      val port = server.start()
      assert(port > 0, s"Expected positive port, got $port")
    } finally {
      server.shutdown()
    }
  }

  test("shutdown stops the server") {
    val server = new BazelNativeBesServer(mkTranslator())
    val port = server.start()

    server.shutdown()

    intercept[Exception] {
      val socket = new Socket("localhost", port)
      socket.close()
    }
  }

  test("port is accessible after start") {
    val server = new BazelNativeBesServer(mkTranslator())

    assertEquals(server.port, -1)
    try {
      server.start()
      assert(server.port > 0)
    } finally {
      server.shutdown()
    }
  }
}
