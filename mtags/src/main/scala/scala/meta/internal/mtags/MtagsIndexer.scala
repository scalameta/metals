package scala.meta.internal.mtags

import scala.{meta => m}

import scala.meta.Name
import scala.meta.Term
import scala.meta.inputs.Input
import scala.meta.internal.inputs._
import scala.meta.internal.semanticdb.Language
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.SymbolInformation.Kind
import scala.meta.internal.{semanticdb => s}

trait MtagsIndexer {
  protected def convertWithContext(
      owner: String,
      convertTextToLink: String => String
  )(text: String): String = {
    if (text.contains('.')) {
      val symbol = text.replace('.', '/') + "."
      convertTextToLink(symbol)
    } else {
      /* If we only have name of the symbol we don't know what
       * kind of symbol that is, so this is our best guess.
       * We should be able to improve it when doing go to definition.
       */
      val ending =
        if (text.endsWith("$")) "."
        else if (text.head.isUpper) "#"
        else "()."
      convertTextToLink(owner + text + ending)
    }
  }
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
  // This method is intentionally non-final to allow accessing this stream directly without building a s.TextDocument.
  def visitOccurrence(
      occ: s.SymbolOccurrence,
      info: s.SymbolInformation,
      owner: String
  ): Unit = {
    names += occ
    symbols += info
  }
  private val names = List.newBuilder[s.SymbolOccurrence]
  private val symbols = List.newBuilder[s.SymbolInformation]

  var currentOwner: String = Symbols.EmptyPackage
  private var myLastCurrentOwner: String = currentOwner
  def lastCurrentOwner: String = myLastCurrentOwner

  def owner: String = currentOwner
  def withOwner[A](owner: String = currentOwner)(thunk: => A): A = {
    val old = currentOwner
    currentOwner = owner
    val result = thunk
    currentOwner = old
    result
  }
  def ownerCompanion: String = {
    import DefinitionAlternatives._
    Symbol(currentOwner) match {
      case GlobalSymbol(owner, Descriptor.Term(name)) =>
        Symbols.Global(
          owner.value,
          Descriptor.Type(name)
        )
      case GlobalSymbol(owner, Descriptor.Type(name)) =>
        Symbols.Global(
          owner.value,
          Descriptor.Term(name)
        )
    }
  }
  def term(name: String, pos: m.Position, kind: Kind, properties: Int): String =
    addSignature(Descriptor.Term(name), pos, kind, properties)
  def term(name: Term.Name, kind: Kind, properties: Int): String =
    addSignature(Descriptor.Term(name.value), name.pos, kind, properties)
  def tparam(name: Name, kind: Kind, properties: Int): String =
    addSignature(
      Descriptor.TypeParameter(name.value),
      name.pos,
      kind,
      properties
    )
  def param(name: Name, kind: Kind, properties: Int): String =
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
  ): String =
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
  ): String =
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
  ): String = {
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
  def tpe(name: String, pos: m.Position, kind: Kind, properties: Int): String =
    addSignature(Descriptor.Type(name), pos, kind, properties)
  def tpe(name: Name, kind: Kind, properties: Int): String =
    addSignature(Descriptor.Type(name.value), name.pos, kind, properties)
  def pkg(name: String, pos: m.Position): String = {
    addSignature(Descriptor.Package(name), pos, Kind.PACKAGE, 0)
  }
  def pkg(ref: Term): Unit =
    ref match {
      case Name(name) =>
        currentOwner = symbol(Descriptor.Package(name))
      case Term.Select(qual, Name(name)) =>
        pkg(qual)
        currentOwner = symbol(Descriptor.Package(name))
    }
  private def addSignature(
      signature: Descriptor,
      definition: m.Position,
      kind: s.SymbolInformation.Kind,
      properties: Int
  ): String = {
    val previousOwner = currentOwner
    currentOwner = symbol(signature)
    myLastCurrentOwner = currentOwner
    val syntax = currentOwner
    val role =
      if (kind.isPackage) s.SymbolOccurrence.Role.REFERENCE
      else s.SymbolOccurrence.Role.DEFINITION
    val occ = s.SymbolOccurrence(
      range = Some(definition.toRange),
      syntax,
      role
    )
    val info = s.SymbolInformation(
      symbol = syntax,
      language = language,
      kind = kind,
      properties = properties,
      displayName = signature.name.value
    )
    visitOccurrence(occ, info, previousOwner)
    syntax
  }
  def symbol(signature: Descriptor): String =
    if (currentOwner.eq(Symbols.EmptyPackage) && signature.isPackage)
      Symbols.Global(Symbols.RootPackage, signature)
    else Symbols.Global(currentOwner, signature)
}
