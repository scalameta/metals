package scala.meta.internal.metals.urlstreamhandler

import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler

import scala.meta.internal.metals.filesystem.MetalsFileSystem
import scala.meta.internal.metals.filesystem.MetalsFileSystemProvider

final object MetalsURLStreamHandler extends URLStreamHandler {

  override protected def openConnection(url: URL): URLConnection =
    if (MetalsFileSystemProvider.scheme == url.getProtocol)
      MetalsFileSystem.metalsFS
        .getOriginalJarURL(url)
        .map(new MetalsURLConnection(_, url))
        .orNull
    else null
}
