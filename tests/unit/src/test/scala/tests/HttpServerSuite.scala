package tests

import java.net.InetSocketAddress
import java.net.ServerSocket
import scala.meta.internal.metals.Cancelable
import scala.meta.internal.metals.MetalsHttpServer

object HttpServerSuite extends BaseSuite {
  test("freePort") {
    val host = "127.0.0.1"
    val sockets = 1.to(3).map { _ =>
      val x = MetalsHttpServer.freePort(host, 4000)
      val socket = new ServerSocket()
      // bind port so that it's unavailable for the next request.
      socket.bind(new InetSocketAddress(host, x))
      socket
    }
    try {
      val ports = sockets.map(_.getLocalPort)
      assertEquals(ports.distinct.length, ports.length)
    } finally {
      Cancelable.cancelEach(sockets)(_.close())
    }
  }
}
