package scala.meta.internal.metals.urlstreamhandler

import java.io.InputStream
import java.net.URL
import java.net.URLConnection

final class MetalsURLConnection(jarURL: URL, metalsURL: URL)
    extends URLConnection(metalsURL) {

  private val jarURLConnection = jarURL.openConnection()

  override def connect(): Unit = jarURLConnection.connect()

  override def getInputStream(): InputStream =
    jarURLConnection.getInputStream()
}
