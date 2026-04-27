package tests.bazelnative

import java.net.ConnectException
import java.net.Socket

import munit.FunSuite

import scala.meta.internal.builds.bazelnative.BazelNativeBepTranslator
import scala.meta.internal.builds.bazelnative.BazelNativeBesServer

class BazelNativeBesServerSuite extends FunSuite {

  test("start binds to ephemeral port") {
    val mockClient = new BazelNativeMockClient()
    val translator = new BazelNativeBepTranslator(mockClient)
    val server = new BazelNativeBesServer(translator)

    try {
      val port = server.start()
      assert(port > 0, s"Expected positive port, got $port")
      assert(server.isRunning)
    } finally {
      server.shutdown()
    }
  }

  test("shutdown stops the server") {
    val mockClient = new BazelNativeMockClient()
    val translator = new BazelNativeBepTranslator(mockClient)
    val server = new BazelNativeBesServer(translator)

    val port = server.start()
    assert(server.isRunning)

    server.shutdown()
    assert(!server.isRunning)

    // After shutdown, connecting should fail
    intercept[ConnectException] {
      val socket = new Socket("localhost", port)
      socket.close()
    }
  }

  test("port is accessible after start") {
    val mockClient = new BazelNativeMockClient()
    val translator = new BazelNativeBepTranslator(mockClient)
    val server = new BazelNativeBesServer(translator)

    assertEquals(server.port, -1)
    try {
      server.start()
      assert(server.port > 0)
    } finally {
      server.shutdown()
    }
  }
}
