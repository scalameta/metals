package scala.meta.metals.mtags

import scala.meta.Name
import scala.meta.Term
import scala.meta.metals.ScalametaEnrichments._
import org.{langmeta => m}
import org.langmeta.semanticdb.Signature
import org.langmeta.semanticdb.Symbol
import scala.meta.internal.semanticdb3.Language
import scala.meta.internal.{semanticdb3 => s}
import scala.meta.internal.semanticdb3.SymbolInformation.Kind

trait MtagsIndexer {
  def language: Language
  def indexRoot(): Unit
  def index(): (List[s.SymbolOccurrence], List[s.SymbolInformation]) = {
    indexRoot()
    names.result() -> symbols.result()
  }
  private val root: Symbol.Global =
    Symbol.Global(Symbol.None, Signature.Term("_root_"))
  var currentOwner: Symbol.Global = root
  def owner = currentOwner
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
    addSignature(
      Signature.TermParameter(name.value),
      name.pos,
      kind,
      properties
    )
  def method(
      name: Name,
      disambiguator: String,
      kind: Kind,
      properties: Int
  ): Unit =
    addSignature(
      Signature.Method(name.value, disambiguator),
      name.pos,
      kind,
      properties
    )
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
  private val names = List.newBuilder[s.SymbolOccurrence]
  private val symbols = List.newBuilder[s.SymbolInformation]
  private def addSignature(
      signature: Signature,
      definition: m.Position,
      kind: s.SymbolInformation.Kind,
      properties: Int
  ): Unit = {
    currentOwner = symbol(signature)
    val syntax = currentOwner.syntax
    val role =
      if (kind.isPackage) s.SymbolOccurrence.Role.REFERENCE
      else s.SymbolOccurrence.Role.DEFINITION
    names += s.SymbolOccurrence(
      range = Some(definition.toSchemaRange),
      syntax,
      role
    )
    symbols += s.SymbolInformation(
      symbol = syntax,
      language = language,
      kind = kind,
      properties = properties,
      name = signature.name
    )
  }
  private def symbol(signature: Signature): Symbol.Global =
    Symbol.Global(currentOwner, signature)
}
