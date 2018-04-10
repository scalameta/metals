package scala.meta.metals.index

import org.langmeta.{lsp => l}
import scala.meta.metals.Uri
import scala.meta.internal.{semanticdb3 => s}

case class SymbolData(
    symbol: String,
    definition: Option[l.Location],
    references: Map[Uri, Seq[l.Range]],
    info: Option[s.SymbolInformation]
)

//message SymbolData {
//  string symbol = 1;
//  // The Position where this symbol is defined.
//  Position definition = 2;
//  map<string, Ranges> references = 3;
//  int32 kind = 4;
//  string name = 5;
//  string signature = 6;
//  // Planned for Scalameta v2.2, see https://github.com/scalameta/scalameta/milestone/9
//  // string docstring = 7; // javadoc/scaladoc
//  // string overrides = 8; // parent symbol that this symbol overrides
//}
