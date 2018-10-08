package scala.meta.internal.mtags

import scala.meta.internal.semanticdb.Scala._

object DefinitionAlternatives {

  /** Returns a list of fallback symbols that can act instead of given symbol. */
  def apply(symbol: String): List[String] = {
    List(
      caseClassCompanionToType(symbol),
      caseClassApplyOrCopy(symbol),
      caseClassApplyOrCopyParams(symbol),
      methodToVal(symbol),
      methodOwner(symbol),
    ).flatten
  }

  private object GlobalSymbol {
    def apply(owner: String, desc: Descriptor): String =
      Symbols.Global(owner, desc)
    def unapply(sym: String): Option[(String, Descriptor)] =
      Some(sym.owner -> sym.desc)
  }

  /** If `case class A(a: Int)` and there is no companion object, resolve
   * `A` in `A(1)` to the class definition.
   */
  private def caseClassCompanionToType(symbol: String): Option[String] =
    Option(symbol).collect {
      case GlobalSymbol(owner, Descriptor.Term(name)) =>
        GlobalSymbol(owner, Descriptor.Type(name))
    }

  /** If `case class Foo(a: Int)`, then resolve
   * `a` in `Foo.apply(a = 1)`, and
   * `a` in `Foo(1).copy(a = 2)`
   * to the `Foo.a` primary constructor definition.
   */
  private def caseClassApplyOrCopyParams(symbol: String): Option[String] =
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

  /** If `case class Foo(a: Int)`, then resolve
   * `apply` in `Foo.apply(1)`, and
   * `copy` in `Foo(1).copy(a = 2)`
   * to the `Foo` class definition.
   */
  private def caseClassApplyOrCopy(symbol: String): Option[String] =
    Option(symbol).collect {
      case GlobalSymbol(
          GlobalSymbol(owner, signature),
          Descriptor.Method("apply" | "copy", _)
          ) =>
        GlobalSymbol(owner, Descriptor.Type(signature.name.value))
    }

  /** Fallback to the val term for a def with multiple params */
  private def methodToVal(symbol: String): Option[String] =
    Option(symbol).collect {
      case GlobalSymbol(owner, Descriptor.Method(name, _)) =>
        GlobalSymbol(owner, Descriptor.Term(name))
    }

  /** For methods and vals, fall back to the enclosing class */
  private def methodOwner(symbol: String): Option[String] =
    Option(symbol).flatMap {
      case GlobalSymbol(owner, _: Descriptor.Method | _: Descriptor.Term) =>
        Some(owner)
      case GlobalSymbol(owner, _: Descriptor.Parameter) =>
        methodOwner(owner)
      case _ =>
        None
    }

}
