package scala.meta.languageserver.ctags

import scala.meta._
import scala.meta.languageserver.ScalametaEnrichments._

trait CtagsIndexer {
  def language: String
  def indexRoot(): Unit
  def index(): (List[ResolvedName], List[ResolvedSymbol]) = {
    indexRoot()
    names.result() -> symbols.result()
  }
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
  def term(name: String, pos: Position, flags: Long): Unit =
    addSignature(Signature.Term(name), pos, flags)
  def term(name: Term.Name, flags: Long): Unit =
    addSignature(Signature.Term(name.value), name.pos, flags)
  def tpe(name: String, pos: Position, flags: Long): Unit =
    addSignature(Signature.Type(name), pos, flags)
  def tpe(name: Type.Name, flags: Long): Unit =
    addSignature(Signature.Type(name.value), name.pos, flags)
  def pkg(ref: Term): Unit = ref match {
    case Name(name) =>
      currentOwner = symbol(Signature.Term(name))
    case Term.Select(qual, Name(name)) =>
      pkg(qual)
      currentOwner = symbol(Signature.Term(name))
  }
  private val root: Symbol.Global =
    Symbol.Global(Symbol.None, Signature.Term("_root_"))
  sealed abstract class Next
  case object Stop extends Next
  case object Continue extends Next
  private val names = List.newBuilder[ResolvedName]
  private val symbols = List.newBuilder[ResolvedSymbol]
  var currentOwner: Symbol.Global = root
  private def addSignature(
      signature: Signature,
      definition: Position,
      flags: Long
  ): Unit = {
    currentOwner = symbol(signature)
    names += ResolvedName(
      definition,
      currentOwner,
      isDefinition = (flags & PACKAGE) == 0
    )
    symbols += ResolvedSymbol(
      currentOwner,
      Denotation(flags, signature.name, "", Nil)
    )
  }
  private def symbol(signature: Signature): Symbol.Global =
    Symbol.Global(currentOwner, signature)
}
