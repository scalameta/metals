package scala.meta.internal.metals

import java.awt.Desktop
import java.awt.Desktop.Action.BROWSE
import java.net.URI

import scala.sys.process._
import scala.util.Properties
import scala.util.control.NonFatal

object Urls {
  def docs(page: String): String =
    s"https://scalameta.org/metals/docs/$page.html"

  def livereload(baseUrl: String): String =
    s"""<script src="$baseUrl/livereload.js"></script>"""

  /**
   * Opens the user's default browser at the provided URL.
   */
  def openBrowser(url: String): Unit = {
    try {
      if (Properties.isMac) {
        // `open` is preferred over Desktop.getDesktop.browse because it starts a Java application stays open
        // until you manually quit it.
        s"open $url".!!
      } else if (
        Desktop.isDesktopSupported &&
        Desktop.getDesktop.isSupported(BROWSE)
      ) {
        Desktop.getDesktop.browse(URI.create(url))
      } else if (Properties.isLinux) {
        s"xdg-open $url".!!
      } else {
        scribe.error(s"Unable to open browser at url $url")
      }
    } catch {
      case NonFatal(e) =>
        scribe.error(s"open browser: $url", e)
    }
  }

}
