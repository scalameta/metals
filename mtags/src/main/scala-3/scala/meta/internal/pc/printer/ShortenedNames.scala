package scala.meta.internal.pc.printer

import java.{util as ju}

import scala.annotation.tailrec

import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.internal.mtags.WithRenames
import scala.meta.internal.pc.AutoImports
import scala.meta.internal.pc.AutoImports.AutoImportsGenerator
import scala.meta.internal.pc.IndexedContext

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags.*
import dotty.tools.dotc.core.Hashable.Binders
import dotty.tools.dotc.core.Names
import dotty.tools.dotc.core.Names.Name
import dotty.tools.dotc.core.Names.termName
import dotty.tools.dotc.core.Symbols
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.*
import org.eclipse.lsp4j.TextEdit

class ShortenedNames(
    val indexedContext: IndexedContext,
    renames: Map[Symbol, String] = Map.empty,
):

  import ShortenedNames.*

  private val history = collection.mutable.Map.empty[Name, ShortName]

  /**
   * Returns a list of shortened names
   */
  def namesToImport: List[ShortName] =
    import indexedContext.ctx
    history.values.toList.filterNot(name => name.symbol.isRoot)

  /**
   * Returns a list of TextEdits (auto-imports) of the symbols
   * that are shortend by "tryShortenName" method, and cached.
   */
  def imports(autoImportsGen: AutoImportsGenerator): List[TextEdit] =
    namesToImport.flatMap { name =>
      autoImportsGen.forSymbol(name.symbol).toList.flatten
    }.toList

  def lookupSymbols(short: ShortName): List[Symbol] =
    indexedContext.findSymbol(short.name).getOrElse(Nil)

  def tryShortenName(short: ShortName)(using Context): Boolean =
    history.get(short.name) match
      case Some(ShortName(_, other)) => true
      case None =>
        val syms = lookupSymbols(short)
        val isOk = syms.filter(_ != NoSymbol) match
          case Nil =>
            if short.symbol.isStatic || // Java static
              short.symbol.maybeOwner.ownersIterator.forall { s =>
                // ensure the symbol can be referenced in a static manner, without any instance
                s.is(Package) || s.is(Module)
              }
            then
              history(short.name) = short
              true
            else false
          case founds =>
            founds.exists { s =>
              s == short.symbol || s.typeRef.metalsDealias.typeSymbol == short.symbol
            }
        if isOk then
          history(short.name) = short
          true
        else false

  def tryShortenName(name: Option[ShortName])(using Context): Boolean =
    name match
      case Some(short) => tryShortenName(short)
      case None => false

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
  def shortType(longType: Type)(using ctx: Context): WithRenames[Type] =
    val isVisited = collection.mutable.Set.empty[(Type, Option[ShortName])]
    val cached = new ju.HashMap[(Type, Option[ShortName]), WithRenames[Type]]()
    def loopForTypeBounds(tpe: TypeBounds): WithRenames[TypeBounds] =
      tpe match
        case TypeAlias(a) => loop(a, None).map(TypeAlias(_))
        case MatchAlias(a) => loop(a, None).map(MatchAlias(_))
        case TypeBounds(lo, hi) =>
          for
            t1 <- loop(lo, None)
            t2 <- loop(hi, None)
          yield TypeBounds(t1, t2)

    def loop(tpe: Type, name: Option[ShortName]): WithRenames[Type] =
      val key = tpe -> name
      // NOTE: Prevent infinite recursion, see https://github.com/scalameta/metals/issues/749
      if isVisited(key) then return cached.getOrDefault(key, WithRenames(tpe))
      isVisited += key
      val result: WithRenames[Type] = tpe match
        // special case for types which are not designated by a Symbol
        // example: path-dependent types
        case tr: CachedTypeRef
            if !tr.designator
              .isInstanceOf[Symbol] && tr.typeSymbol == NoSymbol =>
          loop(tr.prefix, None).map(
            new CachedTypeRef(_, tr.designator, tr.hash)
          )

        case TypeRef(prefix, designator) =>
          // designator is not necessarily an instance of `Symbol` and it's an instance of `Name`
          // this can be seen, for example, when we are shortening the signature of 3rd party APIs.
          val sym =
            if designator.isInstanceOf[Symbol] then
              designator.asInstanceOf[Symbol]
            else tpe.typeSymbol

          @tailrec
          def processOwners(
              sym: Symbol,
              prev: List[Symbol],
              ownersLeft: List[Symbol],
          ): WithRenames[Type] =
            ownersLeft match
              case Nil =>
                val short = ShortName(sym)
                if tryShortenName(short) then
                  WithRenames(TypeRef(NoPrefix, sym))
                else loop(prefix, Some(short)).map(TypeRef(_, sym))
              case h :: tl =>
                indexedContext.rename(h) match
                  // case where the completing symbol is renamed in the context
                  // for example, we have `import java.lang.{Boolean => JBoolean}` and
                  // complete `java.lang.Boolean`. See `CompletionOverrideSuite`'s `rename'.
                  case Some(rename) =>
                    WithRenames(
                      PrettyType(
                        (rename :: prev.map(_.name)).mkString(".")
                      ),
                      Map(rename -> h.showName),
                    )
                  case None =>
                    processOwners(sym, h :: prev, tl)

          lazy val shortened =
            processOwners(sym, Nil, sym.ownersIterator.toList)
          renames.get(sym.owner) match
            case Some(rename) =>
              val short = ShortName(Names.termName(rename), sym.owner)
              if tryShortenName(short) then
                WithRenames(
                  PrettyType(s"$rename.${sym.name.show}"),
                  Map(rename -> sym.owner.showName),
                )
              else shortened
            case _ => shortened

        case TermRef(prefix, designator) =>
          val sym =
            if designator.isInstanceOf[Symbol] then
              designator.asInstanceOf[Symbol]
            else tpe.termSymbol
          val short = ShortName(sym)
          if tryShortenName(short) then WithRenames(TermRef(NoPrefix, sym))
          else loop(prefix, None).map(TermRef(_, sym))

        case t @ ThisType(tyref) =>
          if tryShortenName(name) then WithRenames(NoPrefix)
          else
            loop(tyref, None).map(short =>
              ThisType.raw(short.asInstanceOf[TypeRef])
            )

        case mt @ MethodTpe(pnames, ptypes, restpe) if mt.isImplicitMethod =>
          for
            ptypesR <- WithRenames.sequence(ptypes.map(loop(_, None)))
            t <- loop(restpe, None)
          yield ImplicitMethodType(pnames, ptypesR, t)

        case mt @ MethodTpe(pnames, ptypes, restpe) =>
          for
            ptypesR <- WithRenames.sequence(ptypes.map(loop(_, None)))
            t <- loop(restpe, None)
          yield MethodType(pnames, ptypesR, t)
        case pl @ PolyType(_, restpe) =>
          for
            bounds <- WithRenames.sequence(
              pl.paramInfos.map(bound =>
                for
                  t1 <- loop(bound.lo, None)
                  t2 <- loop(bound.hi, None)
                yield TypeBounds(t1, t2)
              )
            )
            t <- loop(restpe, None)
          yield PolyType(pl.paramNames, bounds, t)
        case SuperType(thistpe, supertpe) =>
          for
            t1 <- loop(thistpe, None)
            t2 <- loop(supertpe, None)
          yield SuperType(t1, t2)
        case AppliedType(tycon, args) =>
          for
            t <- loop(tycon, None)
            argsT <- WithRenames.sequence(args.map(a => loop(a, None)))
          yield AppliedType(t, argsT)
        case t: TypeBounds => loopForTypeBounds(t)
        case RefinedType(parent, names, infos) =>
          for
            t1 <- loop(parent, None)
            t2 <- loop(infos, None)
          yield RefinedType(t1, names, t2)
        case ExprType(res) =>
          loop(res, None).map(ExprType(_))
        case AnnotatedType(parent, annot) =>
          loop(parent, None).map(AnnotatedType(_, annot))
        case AndType(tp1, tp2) =>
          for
            t1 <- loop(tp1, None)
            t2 <- loop(tp2, None)
          yield AndType(t1, t2)
        case or @ OrType(tp1, tp2) =>
          for
            t1 <- loop(tp1, None)
            t2 <- loop(tp2, None)
          yield OrType(t1, t2, or.isSoft)
        case h @ HKTypeLambda(params, result) =>
          for
            bounds <- WithRenames.sequence(
              params.map(p => loopForTypeBounds(p.paramInfo))
            )
            t <- loop(result, None)
          yield h.newLikeThis(params.map(_.paramName), bounds, t)
        // Replace error type into Any
        // Otherwise, DotcPrinter (more specifically, RefinedPrinter in Dotty) print the error type as
        // <error ....>, that is hard to read for users.
        // It'd be ideal to replace error types with type parameter (see: CompletionOverrideSuite#error) though
        case t if t.isError => WithRenames(ctx.definitions.AnyType)
        case t => WithRenames(t)

      cached.putIfAbsent(key, result)
      result
    end loop
    loop(longType, None)
  end shortType
end ShortenedNames

object ShortenedNames:

  case class ShortName(
      name: Name,
      symbol: Symbol,
  ):
    def isRename(using Context): Boolean = symbol.name.show != name.show

  object ShortName:
    def apply(sym: Symbol)(using ctx: Context): ShortName =
      ShortName(sym.name, sym)

  case class PrettyType(name: String) extends TermType:
    def hash: Int = 0
    def computeHash(bind: Binders) = hash
    override def toString = name

end ShortenedNames
