package scala.meta.internal.mtags

import scala.meta.internal.semanticdb.Scala._

object DefinitionAlternatives {

  /**
   * Returns a list of fallback symbols that can act instead of given symbol. */
  def apply(symbol: Symbol): List[Symbol] = {
    List(
      caseClassCompanionToType(symbol),
      caseClassApplyOrCopy(symbol),
      caseClassApplyOrCopyParams(symbol),
      varGetter(symbol),
      methodOwner(symbol)
    ).flatten
  }

  object GlobalSymbol {
    def apply(owner: Symbol, desc: Descriptor): Symbol =
      Symbol(Symbols.Global(owner.value, desc))
    def unapply(sym: Symbol): Option[(Symbol, Descriptor)] =
      Some(sym.owner -> sym.value.desc)
  }

  /**
   * If `case class A(a: Int)` and there is no companion object, resolve
   * `A` in `A(1)` to the class definition.
   */
  private def caseClassCompanionToType(symbol: Symbol): Option[Symbol] =
    Option(symbol).collect {
      case GlobalSymbol(owner, Descriptor.Term(name)) =>
        GlobalSymbol(owner, Descriptor.Type(name))
    }

  /**
   * If `case class Foo(a: Int)`, then resolve
   * `a` in `Foo.apply(a = 1)`, and
   * `a` in `Foo(1).copy(a = 2)`
   * to the `Foo.a` primary constructor definition.
   */
  private def caseClassApplyOrCopyParams(symbol: Symbol): Option[Symbol] =
    Option(symbol).collect {
      case GlobalSymbol(
            GlobalSymbol(
              GlobalSymbol(owner, signature),
              Descriptor.Method("copy" | "apply", _)
            ),
            Descriptor.Parameter(param)
          ) =>
        GlobalSymbol(
          GlobalSymbol(owner, Descriptor.Type(signature.name.value)),
          Descriptor.Term(param)
        )
    }

  /**
   * If `case class Foo(a: Int)`, then resolve
   * `apply` in `Foo.apply(1)`, and
   * `copy` in `Foo(1).copy(a = 2)`
   * to the `Foo` class definition.
   */
  private def caseClassApplyOrCopy(symbol: Symbol): Option[Symbol] =
    Option(symbol).collect {
      case GlobalSymbol(
            GlobalSymbol(owner, signature),
            Descriptor.Method("apply" | "copy", _)
          ) =>
        GlobalSymbol(owner, Descriptor.Type(signature.name.value))
    }

  /**
   * Convert reference to var setter to var getter. */
  private def varGetter(symbol: Symbol): Option[Symbol] =
    Option(symbol).collect {
      case GlobalSymbol(owner, Descriptor.Method(name, disambiguator))
          if name.endsWith("_=") =>
        GlobalSymbol(
          owner,
          Descriptor.Method(name.stripSuffix("_="), disambiguator)
        )
    }

  /**
   * For methods and vals, fall back to the enclosing class
   *
   * This fallback is desirable for cases like
   * - macro annotation generated members
   * - `java/lang/Object#==` and friends
   *
   * The general idea is that we want goto definition to jump somewhere close to
   * the definition if we can't jump to the exact symbol. The risk of false
   * positives is low because if we jump with this fallback method we jump at least
   * to the source file where that symbol is defined. We can't jump to a totally
   * unrelated source file.
   */
  private def methodOwner(symbol: Symbol): Option[Symbol] =
    Option(symbol).flatMap {
      case GlobalSymbol(owner, _: Descriptor.Method | _: Descriptor.Term) =>
        Some(owner)
      case GlobalSymbol(owner, _: Descriptor.Parameter) =>
        methodOwner(owner)
      case _ =>
        None
    }

}
