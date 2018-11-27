package scala.meta.internal.metals

import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketException
import org.scalasbt.ipcsocket.UnixDomainSocket

/**
 * We communicate with Bloop via one of.
 *
 * - unix domain sockets (macos/linux)
 * - tcp (current default on Windows)
 * - named pipes (ideal default on Windows)
 *
 * This class abstracts over each of these.
 */
sealed trait BloopSocket extends Cancelable {
  import BloopSocket._
  def input: InputStream = this match {
    case Unix(socket) => socket.getInputStream
    case NamedPipe(socket) => socket.getInputStream
    case Tcp(socket) =>
      new InputStream {
        override def read(): Int = {
          try socket.getInputStream.read()
          catch {
            case e: SocketException =>
              scribe.debug("tcp input socket closed", e)
              -1
          }
        }
      }
  }
  def output: OutputStream = this match {
    case Unix(socket) => socket.getOutputStream
    case NamedPipe(socket) => socket.getOutputStream
    case Tcp(socket) =>
      new OutputStream {
        private var isClosed = false
        override def write(b: Int): Unit = {
          try {
            if (!isClosed) {
              socket.getOutputStream.write(b)
            }
          } catch {
            case e: SocketException =>
              scribe.debug("tcp output socket closed", e)
              isClosed = true
          }
        }
      }
  }
  import BloopSocket._
  override def cancel(): Unit = this match {
    case NamedPipe(socket) =>
      socket.close()
    case Unix(socket) =>
      if (!socket.isInputShutdown) socket.shutdownInput()
      if (!socket.isOutputShutdown) socket.shutdownOutput()
      socket.close()
    case Tcp(socket) =>
      if (!socket.isClosed) {
        socket.close()
      }
  }
}

object BloopSocket {
  case class Unix(socket: UnixDomainSocket) extends BloopSocket
  case class Tcp(socket: Socket) extends BloopSocket
  case class NamedPipe(socket: Socket) extends BloopSocket
}
