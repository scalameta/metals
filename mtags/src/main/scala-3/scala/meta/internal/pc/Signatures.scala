package scala.meta.internal.pc

import java.{util => ju}

import collection.mutable.ListBuffer
import dotty.tools.dotc.core.Symbols._
import dotty.tools.dotc.core.Types._
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Names.Name
import dotty.tools.dotc.core.NameKinds.EvidenceParamName
import scala.meta.internal.mtags.MtagsEnrichments._

import dotty.tools.dotc.core.Flags._

trait Signatures {

  class ShortenedNames(context: Context) {
    val history = collection.mutable.Map.empty[Name, ShortName]

    def lookupSymbol(short: ShortName): Type = {
      context.findRef(short.name)
    }

    def tryShortenName(short: ShortName)(using Context): Boolean = {
      history.get(short.name) match {
        case Some(ShortName(_, other)) => true
        case None =>
          val foundTpe = lookupSymbol(short)
          val syms = List(foundTpe.termSymbol, foundTpe.typeSymbol)
          val isOk = syms.filter(_ != NoSymbol) match {
            case Nil => false
            case founds => founds.exists(_ == short.symbol)
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

  /**
   * Compute detail string for CompletionItem#detail, which is also used by
   * method's signature string in label.
   *
   * for
   * - method: method signature
   *   - e.g. `[A: Ordering](x: List[Int]): A`
   * - class or module: package name that class or module is belonging
   *   - e.g. ` java.lang`
   * - otherwise: its type
   *   - e.g. ` java.lang.String` ` Symbols.Symbol`
   */
  def infoString(sym: Symbol, info: Type, history: ShortenedNames)(using
      Context
  ): String = {
    sym match {
      case m if m.is(Method) =>
        SignaturePrinter(sym, history, info).defaultMethodSignature()
      case _ if sym.isClass || sym.is(Module) =>
        val printer = SymbolPrinter()
        s" ${printer.fullNameString(sym.owner)}"
      case _ =>
        val short = shortType(info, history)
        val printer = SymbolPrinter()
        s" ${printer.typeString(short)}"
    }
  }

  class SignaturePrinter(
      gsym: Symbol,
      shortenedNames: ShortenedNames,
      gtpe: Type
      // includeDocs: Boolean,
      // includeDefaultParam: Boolean = true,
      // printLongType: Boolean = true
  )(using Context) {
    // In case rawParamss is no set, fallback to paramSymss
    private val paramss =
      if (gsym.rawParamss.length != 0) gsym.rawParamss else gsym.paramSymss
    private lazy val implicitParams: List[Symbol] =
      paramss.flatMap(params => params.filter(p => p.is(Implicit)))

    // should be able to filter by `p.name.is(EvidenceParamName)` or `p.name.startsWith(EvidenceParamName.separator)`
    // but it sometimes doesn't work (not sure why), therefore workaround by string comparison
    private lazy val implicitEvidenceParams: Set[Symbol] =
      implicitParams
        .filter(p => p.name.toString.startsWith(EvidenceParamName.separator))
        .toSet

    private lazy val implicitEvidencesByTypeParam
        : collection.mutable.Map[Symbol, ListBuffer[String]] = {
      val result = collection.mutable.Map.empty[Symbol, ListBuffer[String]]
      implicitEvidenceParams.iterator
        .map(_.info)
        .collect {
          // AppliedType(TypeRef(ThisType(TypeRef(NoPrefix,module class reflect)),trait ClassTag),List(TypeRef(NoPrefix,type T)))
          case AppliedType(tycon, TypeRef(_, tparam) :: Nil)
              if tparam.isInstanceOf[Symbol] =>
            (tycon, tparam.asInstanceOf[Symbol])
        }
        .foreach { case (tycon, tparam) =>
          val buf = result.getOrElseUpdate(tparam, ListBuffer.empty[String])
          buf += printer.typeString(shortType(tycon, shortenedNames))
        }
      result
    }

    /**
     * Compute method signature for the given (method) symbol.
     *
     * @return shortend name for types or the type for terms
     *         e.g. "[A: Ordering](a: A, b: B): collection.mutable.Map[A, B]"
     *              ": collection.mutable.Map[A, B]" for no-arg method
     */
    def defaultMethodSignature(): String = {
      val paramLabelss = paramss.flatMap { params =>
        val labels = params.flatMap { param =>
          // Don't show implicit evidence params
          // e.g.
          // from [A: Ordering](a: A, b: A)(implicit evidence$1: Ordering[A])
          // to   [A: Ordering](a: A, b: A): A
          if (implicitEvidenceParams.contains(param)) Nil
          else paramLabel(param) :: Nil
        }

        // Remove empty params
        if (labels.isEmpty) Nil
        else labels.iterator :: Nil
      }.iterator

      methodSignature(paramLabelss, paramss)
    }

    private val printer = SymbolPrinter()

    private val returnType =
      printer.typeString(shortType(gtpe.finalResultType, shortenedNames))

    private def paramLabel(param: Symbol)(using Context): String = {
      val keywordName = printer.nameString(param)
      val paramTypeString = printer.typeString(
        shortType(param.info, shortenedNames)
      )
      if (param.isTypeParam) {
        // pretty context bounds
        // e.g. f[A](a: A, b: A)(implicit evidence$1: Ordering[A])
        // to   f[A: Ordering](a: A, b: A)(implicit evidence$1: Ordering[A])
        val bounds = implicitEvidencesByTypeParam.getOrElse(param, Nil) match {
          case Nil => ""
          case head :: Nil => s":$head"
          case many => many.mkString(": ", ": ", "")
        }
        s"$keywordName$paramTypeString$bounds"
      } else {
        val paramTypeString = printer.typeString(
          shortType(param.info, shortenedNames)
        )
        s"${keywordName}: ${paramTypeString}"
      }
    }

    private def methodSignature(
        paramLabels: Iterator[Iterator[String]],
        paramss: List[List[Symbol]]
    )(using Context) = {
      paramLabels
        .zip(paramss)
        .map { case (params, syms) =>
          Params.paramsKind(syms) match {
            case Params.Kind.TypeParameterKind =>
              params.mkString("[", ", ", "]")
            case Params.Kind.NormalKind =>
              params.mkString("(", ", ", ")")
            case Params.Kind.ImplicitKind =>
              params.mkString(
                "(implicit ",
                ", ",
                ")"
              ) // TODO: distinguish implicit and using
          }
        }
        .mkString("", "", s": ${returnType}")
    }
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
          val short = ShortName(sym)
          TypeRef(loop(prefix, Some(short)), sym)

        case TermRef(prefix, designator) =>
          val sym =
            if (designator.isInstanceOf[Symbol])
              designator.asInstanceOf[Symbol]
            else tpe.termSymbol
          val short = ShortName(sym)
          if (history.tryShortenName(name)) NoPrefix
          else TermRef(loop(prefix, None), sym)

        case ThisType(tyref) =>
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
  case class ShortName(
      name: Name,
      symbol: Symbol
  )
  object ShortName {
    def apply(sym: Symbol)(using ctx: Context): ShortName =
      ShortName(sym.name, sym)
  }
}
