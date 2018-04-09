package scala.meta.metals.providers

import scala.meta.metals.Uri
import org.langmeta.lsp.Hover
import org.langmeta.lsp.RawMarkedString
import scala.meta.metals.search.SymbolIndex
import scala.{meta => m}
import com.typesafe.scalalogging.LazyLogging

object HoverProvider extends LazyLogging {
  def empty: Hover = Hover(Nil, None)
  val Template =
    m.Template(Nil, Nil, m.Self(m.Name.Anonymous(), None), Nil)

  def hover(
      index: SymbolIndex,
      uri: Uri,
      line: Int,
      column: Int
  ): Hover = {
    val result = for {
      (symbol, _) <- index.findSymbol(uri, line, column)
    } yield {
      // TODO: pretty-print SymbolInformation.tpe, blocked by https://github.com/scalameta/scalameta/issues/1479
      val prettyTpe = symbol.syntax
      Hover(
        contents = RawMarkedString(language = "scala", value = prettyTpe) :: Nil,
        range = None
      )
    }
    result.getOrElse(Hover(Nil, None))
  }

}
