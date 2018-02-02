package scala.meta.languageserver.mtags

import scala.meta.Name
import scala.meta.Term
import scala.meta.languageserver.ScalametaEnrichments._
import scala.meta.internal.semanticdb3.Range
import scala.meta.internal.semanticdb3.SymbolInformation
import scala.meta.internal.semanticdb3.SymbolInformation.Kind
import scala.meta.internal.semanticdb3.SymbolOccurrence
import scala.meta.internal.semanticdb3.SymbolOccurrence.Role
import org.{langmeta => m}
import org.langmeta.semanticdb.Signature
import org.langmeta.semanticdb.Symbol

trait MtagsIndexer {
  def language: String
  def indexRoot(): Unit
  def index(): (List[SymbolOccurrence], List[SymbolInformation]) = {
    indexRoot()
    occs.result() -> infos.result()
  }
  private val root: Symbol.Global =
    Symbol.Global(Symbol.None, Signature.Term("_root_"))
  var currentOwner: Symbol.Global = root
  def owner(isStatic: Boolean): Symbol.Global =
    if (isStatic) currentOwner.toTerm
    else currentOwner
  def withOwner[A](owner: Symbol.Global = currentOwner)(thunk: => A): A = {
    val old = currentOwner
    currentOwner = owner
    val result = thunk
    currentOwner = old
    result
  }
  def term(name: String, pos: m.Position, kind: Kind, properties: Int): Unit =
    addSignature(Signature.Term(name), pos, kind, properties)
  def term(name: Term.Name, kind: Kind, properties: Int): Unit =
    addSignature(Signature.Term(name.value), name.pos, kind, properties)
  def param(name: Name, kind: Kind, properties: Int): Unit =
    addSignature(Signature.TermParameter(name.value), name.pos, kind, properties)
  def tpe(name: String, pos: m.Position, kind: Kind, properties: Int): Unit =
    addSignature(Signature.Type(name), pos, kind, properties)
  def tpe(name: Name, kind: Kind, properties: Int): Unit =
    addSignature(Signature.Type(name.value), name.pos, kind, properties)
  def pkg(ref: Term): Unit = ref match {
    case Name(name) =>
      currentOwner = symbol(Signature.Term(name))
    case Term.Select(qual, Name(name)) =>
      pkg(qual)
      currentOwner = symbol(Signature.Term(name))
  }
  private val occs = List.newBuilder[SymbolOccurrence]
  private val infos = List.newBuilder[SymbolInformation]
  private def addSignature(
      signature: Signature,
      definition: m.Position,
      kind: Kind,
      properties: Int
  ): Unit = {
    currentOwner = symbol(signature)
    val syntax = currentOwner.syntax
    occs += SymbolOccurrence(
      Some(Range(definition.startLine, definition.startColumn, definition.endLine, definition.endColumn)),
      syntax,
      if (kind != Kind.PACKAGE) Role.DEFINITION else Role.REFERENCE
    )
    infos += SymbolInformation(
      syntax,
      language,
      kind,
      properties,
      signature.name,
      None,
      None,
      Nil,
      Nil
    )
  }
  private def symbol(signature: Signature): Symbol.Global =
    Symbol.Global(currentOwner, signature)
}
