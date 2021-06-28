package scala.meta.internal.pc

import java.{util => ju}

import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer

import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.pc.AutoImports.AutoImportsGenerator

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags._
import dotty.tools.dotc.core.Hashable.Binders
import dotty.tools.dotc.core.NameKinds
import dotty.tools.dotc.core.NameKinds.EvidenceParamName
import dotty.tools.dotc.core.Names
import dotty.tools.dotc.core.Names.Designator
import dotty.tools.dotc.core.Names.Name
import dotty.tools.dotc.core.Names.SimpleName
import dotty.tools.dotc.core.Symbols
import dotty.tools.dotc.core.Symbols._
import dotty.tools.dotc.core.Types._
import org.eclipse.lsp4j.TextEdit

class ShortenedNames(
    val indexedContext: IndexedContext
) {
  private val history = collection.mutable.Map.empty[Name, ShortName]

  def imports(autoImportsGen: AutoImportsGenerator): List[TextEdit] = {
    history.values.flatMap { name =>
      autoImportsGen.forSymbol(name.symbol).toList.flatten
    }.toList
  }

  def lookupSymbols(short: ShortName): List[Symbol] =
    indexedContext.findSymbol(short.name).getOrElse(Nil)

  def tryShortenName(short: ShortName)(using Context): Boolean = {
    history.get(short.name) match {
      case Some(ShortName(_, other)) => true
      case None =>
        val syms = lookupSymbols(short)
        val isOk = syms.filter(_ != NoSymbol) match {
          case Nil =>
            if (
              short.symbol.isStatic || // Java static
              short.symbol.owner.ownersIterator.forall { s =>
                // ensure the symbol can be referenced in a static manner, without any instance
                s.is(Package) || s.is(Module)
              }
            ) {
              history(short.name) = short
              true
            } else false
          case founds =>
            founds.exists(s =>
              s == short.symbol || s.typeRef.dealias.typeSymbol == short.symbol
            )
        }
        if (isOk) {
          history(short.name) = short
          true
        } else {
          false
        }
    }
  }

  def tryShortenName(name: Option[ShortName])(using Context): Boolean =
    name match {
      case Some(short) => tryShortenName(short)
      case None => false
    }
}

case class ShortName(
    name: Name,
    symbol: Symbol
)
object ShortName {
  def apply(sym: Symbol)(using ctx: Context): ShortName =
    ShortName(sym.name, sym)
}

case class PrettyType(name: String) extends Type {
  def hash: Int = 0
  def computeHash(bind: Binders) = hash
  override def toString = name
}

/**
 * Shorten the long (fully qualified) type to shorter representation, so printers
 * can obtain more readable form of type like `SrcPos` instead of `dotc.util.SrcPos`
 * (if the name can be resolved from the context).
 *
 * For example,
 * when the longType is like `TypeRef(TermRef(ThisType(TypeRef(NoPrefix,module class dotc)),module util),SrcPos)`,
 * if `dotc.util.SrcPos` found from the scope, then `TypeRef(NoPrefix, SrcPos)`
 * if not, and `dotc.util` found from the scope then `TypeRef(TermRef(NoPrefix, module util), SrcPos)`
 *
 * @see Scala 3/Internals/Type System https://dotty.epfl.ch/docs/internals/type-system.html
 */
def shortType(longType: Type, history: ShortenedNames)(using
    ctx: Context
): Type = {
  val isVisited = collection.mutable.Set.empty[(Type, Option[ShortName])]
  val cached = new ju.HashMap[(Type, Option[ShortName]), Type]()

  def loop(tpe: Type, name: Option[ShortName]): Type = {
    val key = tpe -> name
    // NOTE: Prevent infinite recursion, see https://github.com/scalameta/metals/issues/749
    if (isVisited(key)) return cached.getOrDefault(key, tpe)
    isVisited += key
    val result = tpe match {
      case TypeRef(prefix, designator) =>
        // designator is not necessarily an instance of `Symbol` and it's an instance of `Name`
        // this can be seen, for example, when we are shortening the signature of 3rd party APIs.
        val sym =
          if (designator.isInstanceOf[Symbol])
            designator.asInstanceOf[Symbol]
          else tpe.typeSymbol

        @tailrec
        def processOwners(
            sym: Symbol,
            prev: List[Symbol],
            ownersLeft: List[Symbol]
        ): Type = {
          ownersLeft match {
            case Nil =>
              val short = ShortName(sym)
              TypeRef(loop(prefix, Some(short)), sym)
            case h :: tl =>
              history.indexedContext.rename(h.name.toSimpleName) match {
                case Some(rename) =>
                  PrettyType(
                    (rename :: prev.map(_.name)).mkString(".")
                  )
                case None =>
                  processOwners(sym, h :: prev, tl)
              }
          }
        }

        processOwners(sym, Nil, sym.ownersIterator.toList)

      case TermRef(prefix, designator) =>
        val sym =
          if (designator.isInstanceOf[Symbol])
            designator.asInstanceOf[Symbol]
          else tpe.termSymbol
        val short = ShortName(sym)
        if (history.tryShortenName(name)) NoPrefix
        else TermRef(loop(prefix, None), sym)

      case t @ ThisType(tyref) =>
        if (history.tryShortenName(name)) NoPrefix
        else ThisType.raw(loop(tyref, None).asInstanceOf[TypeRef])

      case mt @ MethodTpe(pnames, ptypes, restpe) if mt.isImplicitMethod =>
        ImplicitMethodType(
          pnames,
          ptypes.map(loop(_, None)),
          loop(restpe, None)
        )
      case mt @ MethodTpe(pnames, ptypes, restpe) =>
        MethodType(pnames, ptypes.map(loop(_, None)), loop(restpe, None))

      case pl @ PolyType(_, restpe) =>
        PolyType(
          pl.paramNames,
          pl.paramInfos.map(bound =>
            TypeBounds(loop(bound.lo, None), loop(bound.hi, None))
          ),
          loop(restpe, None)
        )
      case ConstantType(value) => value.tpe
      case SuperType(thistpe, supertpe) =>
        SuperType(loop(thistpe, None), loop(supertpe, None))
      case AppliedType(tycon, args) =>
        AppliedType(loop(tycon, None), args.map(a => loop(a, None)))
      case TypeBounds(lo, hi) =>
        TypeBounds(loop(lo, None), loop(hi, None))
      case ExprType(res) =>
        ExprType(loop(res, None))
      case AnnotatedType(parent, annot) =>
        AnnotatedType(loop(parent, None), annot)
      case AndType(tp1, tp2) =>
        AndType(loop(tp1, None), loop(tp2, None))
      case or @ OrType(tp1, tp2) =>
        OrType(loop(tp1, None), loop(tp2, None), or.isSoft)
      case t => t
    }

    cached.putIfAbsent(key, result)
    result
  }
  loop(longType, None)
}
