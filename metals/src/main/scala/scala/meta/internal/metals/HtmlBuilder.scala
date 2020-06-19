package scala.meta.internal.metals

import java.lang.StringBuilder

import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType

/**
 * A string builder with helper methods for rendering HTML.
 *
 * We don't use a library like Scalatags because we are trying to keep
 * the number of external Scala dependencies low.
 */
final class HtmlBuilder() {
  private val sb = new StringBuilder()
  def render: String = sb.toString
  override def toString: String = render

  def section(title: String, content: HtmlBuilder => Unit): HtmlBuilder = {
    element(
      "section",
      "class='container with-title' style='margin-bottom: .75rem'"
    )(
      _.element("h2", "class='title'")(_.text(title))
        .element("div")(content)
    )
  }
  def page(title: String, raw: String = "")(
      body: HtmlBuilder => Unit
  ): HtmlBuilder = {
    element("html")(
      _.element("head")(
        _.element("title")(_.text(title))
          .raw("""<meta charset="UTF-8">""")
          .raw(raw)
          .raw(
            s"""<link href="https://unpkg.com/nes.css@0.0.2/css/nes.min.css" rel="stylesheet" />"""
          )
      ).element("body", "style='padding: .75rem; font-size: 10px'")(body)
    )
  }

  def submitButton(query: String, title: String): HtmlBuilder =
    element("form", s"action='/complete?$query' method='post'")(
      _.element("button", "type='submit' class='btn'")(_.text(title))
    )

  def unorderedList[T](
      iterable: Iterable[T]
  )(fn: T => Unit): HtmlBuilder = {
    sb.append("<ul>")
    iterable.foreach { item =>
      sb.append("<li>")
      fn(item)
      sb.append("</li>")
    }
    sb.append("</ul>")
    this
  }

  private def color(tpe: MessageType): String =
    tpe match {
      case MessageType.Error => "#f44336"
      case MessageType.Warning => "#ff9800"
      case MessageType.Info => "#3f51b5"
      case _ => "#009688"
    }

  def path(p: AbsolutePath): HtmlBuilder = {
    raw("</br>").text(p.toString())
  }

  def call(fn: HtmlBuilder => Unit): HtmlBuilder = {
    fn(this)
    this
  }

  def append(params: MessageParams): HtmlBuilder = {
    element("font", s"color='${color(params.getType)}'")(
      _.text(params.getType.toString.toLowerCase())
    ).text(" ")
      .text(params.getMessage)
      .raw("\n")
  }

  def element(
      name: String,
      attr: String = ""
  )(fn: HtmlBuilder => Unit): this.type = {
    sb.append("<")
      .append(name)
      .append(" ")
      .append(attr)
      .append(">")
    fn(this)
    sb.append("</")
      .append(name)
      .append(">")
    this
  }

  def raw(string: String): this.type = {
    sb.append(string)
    this
  }
  def text(string: String): this.type = {
    sb.append(escape(string))
    this
  }

  private def escape(s: String): String = {
    val out = new StringBuilder(Math.max(16, s.length()))
    var i = 0
    while (i < s.length) {
      val ch = s.charAt(i)
      if (ch > 127 || ch == '"' || ch == '<' || ch == '>' || ch == '&') {
        out.append("&#")
        out.append(ch.toInt)
        out.append(';')
      } else {
        out.append(ch)
      }
      i += 1
    }
    out.toString
  }
}
