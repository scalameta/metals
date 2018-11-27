package scala.meta.internal.mtags

import scala.meta.Name
import scala.meta.Term
import scala.meta.inputs.Input
import scala.{meta => m}
import scala.meta.internal.semanticdb.Language
import scala.meta.internal.{semanticdb => s}
import scala.meta.internal.semanticdb.SymbolInformation.Kind
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.inputs._

trait MtagsIndexer {
  def language: Language
  def indexRoot(): Unit
  def input: Input.VirtualFile
  def index(): s.TextDocument = {
    indexRoot()
    s.TextDocument(
      uri = input.path,
      text = input.text,
      language = language,
      occurrences = names.result(),
      symbols = symbols.result()
    )
  }
  private val root: String =
    Symbols.RootPackage
  var currentOwner: String = root
  def owner = currentOwner
  def withOwner[A](owner: String = currentOwner)(thunk: => A): A = {
    val old = currentOwner
    currentOwner = owner
    val result = thunk
    currentOwner = old
    result
  }
  def term(name: String, pos: m.Position, kind: Kind, properties: Int): Unit =
    addSignature(Descriptor.Term(name), pos, kind, properties)
  def term(name: Term.Name, kind: Kind, properties: Int): Unit =
    addSignature(Descriptor.Term(name.value), name.pos, kind, properties)
  def tparam(name: Name, kind: Kind, properties: Int): Unit =
    addSignature(
      Descriptor.TypeParameter(name.value),
      name.pos,
      kind,
      properties
    )
  def param(name: Name, kind: Kind, properties: Int): Unit =
    addSignature(
      Descriptor.Parameter(name.value),
      name.pos,
      kind,
      properties
    )
  def ctor(
      disambiguator: String,
      pos: m.Position,
      properties: Int
  ): Unit =
    addSignature(
      Descriptor.Method(Names.Constructor.value, disambiguator),
      pos,
      Kind.CONSTRUCTOR,
      properties
    )
  def method(
      name: String,
      disambiguator: String,
      pos: m.Position,
      properties: Int
  ): Unit =
    addSignature(
      Descriptor.Method(name, disambiguator),
      pos,
      Kind.METHOD,
      properties
    )
  def method(
      name: Name,
      disambiguator: String,
      kind: Kind,
      properties: Int
  ): Unit = {
    val methodName = name match {
      case Name.Anonymous() => Names.Constructor.value
      case _ => name.value
    }
    addSignature(
      Descriptor.Method(methodName, disambiguator),
      name.pos,
      kind,
      properties
    )
  }
  def tpe(name: String, pos: m.Position, kind: Kind, properties: Int): Unit =
    addSignature(Descriptor.Type(name), pos, kind, properties)
  def tpe(name: Name, kind: Kind, properties: Int): Unit =
    addSignature(Descriptor.Type(name.value), name.pos, kind, properties)
  def pkg(name: String, pos: m.Position): Unit = {
    addSignature(Descriptor.Package(name), pos, Kind.PACKAGE, 0)
  }
  def pkg(ref: Term): Unit = ref match {
    case Name(name) =>
      currentOwner = symbol(Descriptor.Package(name))
    case Term.Select(qual, Name(name)) =>
      pkg(qual)
      currentOwner = symbol(Descriptor.Package(name))
  }
  private val names = List.newBuilder[s.SymbolOccurrence]
  private val symbols = List.newBuilder[s.SymbolInformation]
  private def addSignature(
      signature: Descriptor,
      definition: m.Position,
      kind: s.SymbolInformation.Kind,
      properties: Int
  ): Unit = {
    currentOwner = symbol(signature)
    val syntax = currentOwner
    val role =
      if (kind.isPackage) s.SymbolOccurrence.Role.REFERENCE
      else s.SymbolOccurrence.Role.DEFINITION
    names += s.SymbolOccurrence(
      range = Some(definition.toRange),
      syntax,
      role
    )
    symbols += s.SymbolInformation(
      symbol = syntax,
      language = language,
      kind = kind,
      properties = properties,
      displayName = signature.name.value
    )
  }
  def symbol(signature: Descriptor): String =
    Symbols.Global(currentOwner, signature)
}
