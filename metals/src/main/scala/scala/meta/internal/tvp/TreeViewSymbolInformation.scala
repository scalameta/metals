package scala.meta.internal.tvp

import scala.meta.internal.semanticdb._
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.SymbolInformation.{Kind => k}
import scala.meta.internal.mtags.MtagsEnrichments._

case class TreeViewSymbolInformation(
    symbol: String,
    kind: SymbolInformation.Kind,
    properties: Int = 0
) {
  def isVal: Boolean = kind.isMethod && properties.isVal
  def isVar: Boolean = kind.isMethod && properties.isVar
  def parents: List[TreeViewSymbolInformation] = {
    def loop(s: String): List[TreeViewSymbolInformation] =
      if (s == Symbols.RootPackage || s.isNone) Nil
      else {
        val info = TreeViewSymbolInformation(
          s,
          if (s.isPackage) k.PACKAGE
          else if (s.isTerm) k.OBJECT
          else if (s.isType) k.CLASS
          else if (s.isTypeParameter) k.TYPE_PARAMETER
          else k.UNKNOWN_KIND,
          0
        )
        info :: loop(s.owner)
      }
    loop(symbol)
  }
}
