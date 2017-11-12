package a

import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.util.format.options.ListBulletMarker

case class User(name: String, age: Int, c: HtmlRenderer.Builder)

object a {
  HtmlRenderer.ESCAPE_HTML_BLOCKS
  ListBulletMarker.ANY
  List(1, 2).map(_ + 2)
}
