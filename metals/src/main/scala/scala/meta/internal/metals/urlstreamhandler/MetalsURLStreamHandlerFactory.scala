package scala.meta.internal.metals.urlstreamhandler

import java.net.URL
import java.net.URLStreamHandler
import java.net.URLStreamHandlerFactory

import scala.meta.internal.metals.filesystem.MetalsFileSystemProvider

final object MetalsURLStreamHandlerFactory extends URLStreamHandlerFactory {

  private var registered: Boolean = false

  def register: Unit = {
    if (!registered)
      synchronized {
        if (!registered) {
          URL.setURLStreamHandlerFactory(MetalsURLStreamHandlerFactory);
          registered = true
        }
      }
  }

  override def createURLStreamHandler(protocol: String): URLStreamHandler =
    if (MetalsFileSystemProvider.scheme == protocol) MetalsURLStreamHandler
    else null
}
