package scala.meta.internal.pantsbuild.commands

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import scala.meta.io.AbsolutePath

object ZipkinUrls {

  def url: Option[String] =
    Option(System.getProperty("metals.zipkin.server.url"))

  def updateZipkinServerUrl(): Boolean = {
    ZipkinUrls.url match {
      case Some(newUrl) =>
        import scala.meta.internal.metals.MetalsEnrichments._
        val homedir = AbsolutePath(System.getProperty("user.home"))
        val jvmopts = homedir.resolve(".bloop").resolve(".jvmopts")
        val oldOptions =
          if (jvmopts.isFile) jvmopts.readText.linesIterator.toList
          else Nil
        val zipkin = "-Dzipkin.server.url=(.*)".r
        val oldUrl = oldOptions.collectFirst { case zipkin(url) => url }
        if (oldUrl.contains(newUrl)) {
          false
        } else {
          val otherOptions =
            oldOptions.filterNot(_.startsWith("-Dzipkin.server.url")).toList
          val zipkinOption = s"-Dzipkin.server.url=$newUrl"
          val allOptions = zipkinOption :: otherOptions
          scribe.info(s"zipkin: new server URL '$newUrl'")
          Files.write(
            jvmopts.toNIO,
            allOptions.mkString("\n").getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.CREATE
          )
          true
        }
      case None =>
        false
    }
  }
}
