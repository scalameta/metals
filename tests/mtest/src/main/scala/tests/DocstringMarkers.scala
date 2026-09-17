package tests

import scala.util.matching.Regex

import scala.meta.internal.docstrings.MetalsSymbolLink

/**
 * `MarkdownGenerator` marks scaladoc entity (wiki) links with
 * `MetalsSymbolLink.scheme` so the server can later resolve them into navigable
 * command links. Tests that assert on the raw presentation-compiler docstring
 * rendering (hover/completion/signature help, without going through the server)
 * decode the markers back to their plain link target so the assertions stay
 * readable (scalameta/metals#3383).
 */
object DocstringMarkers {
  private val markerRegex: Regex =
    (Regex.quote(MetalsSymbolLink.scheme) + "([^)]*)").r

  def decode(rendered: String): String =
    if (rendered == null || !rendered.contains(MetalsSymbolLink.scheme))
      rendered
    else
      markerRegex.replaceAllIn(
        rendered,
        m =>
          Regex.quoteReplacement(
            MetalsSymbolLink.parsePayload(m.group(1)).target
          )
      )
}
