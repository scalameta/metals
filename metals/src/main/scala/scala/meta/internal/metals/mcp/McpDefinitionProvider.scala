package scala.meta.internal.metals.mcp

import scala.meta.Dialect
import scala.meta.internal.metals.EmptyReportContext
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.SemanticdbDefinition
import scala.meta.internal.metals.mcp.QueryEngine.kindToTypeString
import scala.meta.internal.mtags.GlobalSymbolIndex
import scala.meta.internal.mtags.Symbol
import scala.meta.internal.semanticdb.Scala.Descriptor
import scala.meta.internal.semanticdb.Scala.Symbols
import scala.meta.io.AbsolutePath

class McpDefinitionProvider(index: GlobalSymbolIndex) {

  def forEachDefinition(
      pkg: String,
      name: String,
      shouldVisitFile: AbsolutePath => Boolean,
  )(f: SymbolSearchResult => Unit): Unit = {
    definition(pkg, name).foreach {
      case (path, dialect) if shouldVisitFile(path) =>
        val input = path.toInput
        SemanticdbDefinition.foreach(
          input,
          dialect,
          includeMembers = false,
        ) { semanticdbDefn =>
          lazy val kind =
            kindToTypeString(semanticdbDefn.info.kind.toLsp).getOrElse(
              SymbolType.Unknown(semanticdbDefn.info.kind.toString)
            )
          val fqcn = semanticdbDefn.info.symbol.fqcn
          val searchResult = SymbolSearchResult(
            name = semanticdbDefn.info.displayName,
            path = fqcn,
            symbolType = kind,
          )
          f(searchResult)
        }(EmptyReportContext)
      case _ =>
    }
  }

  private def definition(
      pkg: String,
      nme: String,
  ): List[(AbsolutePath, Dialect)] = {
    val tpe = Symbol(Symbols.Global(pkg, Descriptor.Type(nme)))
    val forTpe = index.findFileForToplevel(tpe)
    val term = Symbol(Symbols.Global(pkg, Descriptor.Term(nme)))
    val forTerm = index.findFileForToplevel(term)
    (forTpe ++ forTerm).distinctBy(_._1)
  }
}
