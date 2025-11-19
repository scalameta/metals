package scala.meta.internal.mtags

import scala.annotation.tailrec
import scala.util.control.NonFatal

import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.semanticdb.Scala._

/**
 * Represents a unique definitions such as a Scala `val`, `object`, `class`, or Java field/method.
 *
 * Examples: {{{
 *   "scala/Predef.String#"
 *   "scala/collection/immutable/`::`#"
 *   "scala/Option#get()."
 *   "scala/Option.apply()."
 * }}}
 *
 * @param value The unique string representation for this symbol.
 */
final class Symbol private (val value: String) {

  def isNone: Boolean = value.isNone
  def isRootPackage: Boolean = value.isRootPackage
  def isEmptyPackage: Boolean = value.isEmptyPackage
  def isGlobal: Boolean = value.isGlobal
  def isLocal: Boolean = value.isLocal
  def isTerm: Boolean = desc.isTerm
  def isMethod: Boolean = desc.isMethod
  def isConstructor: Boolean = desc.isMethod && desc.name.value == "<init>"
  def isType: Boolean = desc.isType
  def isPackage: Boolean = desc.isPackage
  def isParameter: Boolean = desc.isParameter
  def isTypeParameter: Boolean = desc.isTypeParameter
  private lazy val desc: Descriptor = value.desc

  def owner: Symbol = Symbol(value.owner)
  def displayName: String = desc.name.value

  def enclosingPackage: Symbol = {
    def loop(s: Symbol): Symbol = {
      if (s.isPackage || s.isNone) s
      else loop(s.owner)
    }
    loop(this)
  }
  def toplevel: Symbol = {
    if (value.isNone) this
    else if (value.isPackage) this
    else {
      val owner = value.owner
      if (owner.isPackage) this
      else Symbol(owner).toplevel
    }
  }
  def isToplevel: Boolean = {
    !value.isPackage &&
    value.owner.isPackage
  }
  def asNonEmpty: Option[Symbol] =
    if (isNone) None
    else Some(this)

  override def toString: String =
    if (isNone) "<no symbol>"
    else value
  def structure: String =
    if (isNone) "Symbol.None"
    else if (isRootPackage) "Symbol.RootPackage"
    else if (isEmptyPackage) "Symbol.EmptyPackage"
    else s"""Symbol("$value")"""
  override def equals(obj: Any): Boolean =
    this.eq(obj.asInstanceOf[AnyRef]) || (obj match {
      case s: Symbol => value == s.value
      case _ => false
    })
  override def hashCode(): Int = value.##
}

object Symbol {
  val RootPackage: Symbol = new Symbol(Symbols.RootPackage)
  val EmptyPackage: Symbol = new Symbol(Symbols.EmptyPackage)
  val None: Symbol = new Symbol(Symbols.None)
  def apply(sym: String): Symbol = {
    if (sym.isEmpty) Symbol.None
    else new Symbol(sym)
  }

  def validated(sym: String): Either[String, Symbol] = {
    // NOTE(olafur): this validation method is hacky, we should write a proper
    // parser that reports positioned error messages with actionable feedback
    // on how to write correct SemanticDB symbols. This here is better than nothing
    // at least.
    def fail(message: String) =
      Left(
        s"invalid SemanticDB symbol '$sym': ${message} (to learn the syntax " +
          s"see https://scalameta.org/docs/semanticdb/specification.html#symbol-1)"
      )
    def errorMessage(s: String): Option[String] = {
      if (s.isNone) {
        scala.None
      } else {
        s.desc match {
          case Descriptor.None =>
            Option(
              s"missing descriptor, did you mean `$sym/` or `$sym.`?"
            )
          case _ =>
            errorMessage(s.owner)
        }
      }
    }
    try {
      errorMessage(sym) match {
        case Some(error) => fail(error)
        case scala.None => Right(Symbol(sym))
      }
    } catch {
      case NonFatal(e) =>
        fail(e.getMessage)
    }
  }

  object Local {
    def unapply(sym: Symbol): Option[Symbol] =
      if (sym.isLocal) Some(sym)
      else scala.None
  }

  object Global {
    def unapply(sym: Symbol): Option[(Symbol, Symbol)] =
      if (sym.isGlobal) {
        val owner = Symbol(sym.value.owner)
        Some(owner -> sym)
      } else {
        scala.None
      }
  }

  /**
   * Heuristic to guess the symbol from the parts of the identifier.
   *
   * We assume that any lower case parts at the start are packages,
   * everything later is either a class/object and then things
   * the can belong to them. Currently we will not guess if
   * a identifier actually belongs to a class, we assume that
   * we can only check fields inside objects.
   *
   * @param path a.b.c.MyClass.myMethod
   * @param isScala3 true if the path is from a Scala 3 file
   * @return a/b/c/MyClass.myMethod without including the suffix
   */
  def guessFromPath(path: String, isScala3: Boolean): Symbol = {
    guessSymbolFromParts(splitAt(path, '.'), isScala3)
  }

  def guessSymbolFromParts(parts: List[String], isScala3: Boolean): Symbol = {
    val keywordWrapper =
      if (isScala3) KeywordWrapper.Scala3 else KeywordWrapper.Scala2

    val (symbol, _) = parts.foldLeft(("", false)) {
      case ((prefix, insideObject), next) =>
        val name = keywordWrapper.backtickWrap(
          next.replace("\\", ""),
          Set("this", "package"),
          wrapOperators = true
        )
        if (prefix.isEmpty())
          (name, /*insideObject =*/ name.find(_.isLetter).exists(_.isUpper))
        else if (insideObject) (prefix + "." + name, insideObject)
        else if (name.find(_.isLetter).exists(_.isLower))
          (prefix + "/" + name, insideObject)
        else (prefix + "/" + name, /*insideObject =*/ true)
    }
    Symbol(symbol)
  }

  private def splitAt(text: String, c: Char): List[String] = {
    val indices = text.findIndicesOf(List(c))
    splitAt(text, indices)
  }

  @tailrec
  private def splitAt(
      text: String,
      indices: List[Int],
      offset: Int = 0,
      acc: List[String] = List.empty
  ): List[String] = {
    indices match {
      case i :: rest =>
        val (part1, part2) = text.splitAt(i - offset)
        splitAt(part2.tail, rest, i + 1, part1 :: acc)
      case _ => (text :: acc).reverse
    }
  }

}
