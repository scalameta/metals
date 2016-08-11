package langserver.core

import java.io.InputStream
import java.io.OutputStream

abstract class Server(inStream: InputStream, outStream: OutputStream) {
  val msgReader = new MessageReader(inStream)
  
  
}
