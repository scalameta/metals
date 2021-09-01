package scala.meta.internal.pc

import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.Optional

import scala.meta.internal.metals.HtmlBuilder
import scala.meta.internal.io.FileIO
import scala.meta.internal.jdk.CollectionConverters.*
import scala.meta.io.AbsolutePath

import com.google.gson.JsonPrimitive
import dotty.tools.dotc.core.tasty.TastyHTMLPrinter
import dotty.tools.dotc.core.tasty.TastyAnsiiPrinter
import dotty.tools.dotc.core.tasty.TastyPrinter
import org.eclipse.{lsp4j => l}
import scala.meta.internal.io.PathIO

object TastyUtils {
  def getTasty(
      tastyURI: URI,
      isHtmlSupported: Boolean
  ): String =
    if (isHtmlSupported)
      htmlTasty(tastyURI)
    else
      normalTasty(tastyURI)

  def getStandaloneHtmlTasty(tastyURI: URI): String = {
    htmlTasty(tastyURI, List(HtmlBuilder.htmlCSS), HtmlBuilder.bodyStyle)
  }

  private def normalTasty(tastyURI: URI): String = {
    val tastyBytes = AbsolutePath.fromAbsoluteUri(tastyURI).readAllBytes
    new TastyPrinter(tastyBytes).showContents()
  }

  private def htmlTasty(
      tastyURI: URI,
      headElems: List[String] = Nil,
      bodyAttributes: String = ""
  ): String = {
    val title = tastyHtmlPageTitle(tastyURI)
    val tastyBytes = AbsolutePath.fromAbsoluteUri(tastyURI).readAllBytes
    val tastyHtml = new TastyHTMLPrinter(tastyBytes).showContents()
    HtmlBuilder()
      .page(title, htmlStyles :: headElems, bodyAttributes) { builder =>
        builder
          .element("pre")(_.raw(tastyHtml))
      }
      .render
  }

  private def tastyHtmlPageTitle(file: URI) = {
    val filename = PathIO.basename(AbsolutePath.fromAbsoluteUri(file).toString)
    s"TASTy for $filename"
  }

  private val htmlStyles =
    """|<style>
       |  span.name {
       |    color: magenta;
       |  }
       |  span.tree {
       |    color: yellow;
       |  }
       |  span.length {
       |    color: cyan;
       |  }
       |</style>
       |""".stripMargin
}
