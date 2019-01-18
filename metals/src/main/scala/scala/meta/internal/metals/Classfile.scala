package scala.meta.internal.metals

import scala.meta.internal.mtags.OnDemandSymbolIndex
import scala.meta.internal.semanticdb.Scala.Descriptor
import scala.meta.internal.semanticdb.Scala.Symbols
import scala.meta.internal.mtags.Symbol
import scala.meta.internal.mtags.SymbolDefinition

case class Classfile(pkg: String, filename: String) {
  def isExact(query: WorkspaceSymbolQuery): Boolean =
    name == query.query
  def name: String = {
    val dollar = filename.indexOf('$')
    if (dollar < 0) filename.stripSuffix(".class")
    else filename.substring(0, dollar)
  }
  def definition(index: OnDemandSymbolIndex): Option[SymbolDefinition] = {
    val nme = name
    val tpe = Symbol(Symbols.Global(pkg, Descriptor.Type(nme)))
    index.definition(tpe).orElse {
      val term = Symbol(Symbols.Global(pkg, Descriptor.Term(nme)))
      index.definition(term)
    }
  }
}
