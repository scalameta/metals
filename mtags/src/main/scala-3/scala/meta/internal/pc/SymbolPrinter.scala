package scala.meta.internal.pc

import collection.mutable.ListBuffer
import dotty.tools.dotc.core.NameKinds.EvidenceParamName
import dotty.tools.dotc.printing.RefinedPrinter
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Names.*
import dotty.tools.dotc.core.NameOps.*
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.core.Flags

class SymbolPrinter(using ctx: Context) extends RefinedPrinter(ctx):

  private val defaultWidth = 1000

  override def nameString(name: Name): String =
    super.nameString(name.stripModuleClassSuffix)

  def typeString(tpw: Type): String =
    toText(tpw).mkString(defaultWidth, false)

  def fullDefinition(sym: Symbol, tpe: Type): String =
    val isImplicit = sym.is(Flags.Implicit)
    val name = nameString(sym)
    val implicitKeyword = if isImplicit then "implicit " else ""
    keyString(sym) match
      case "" =>
        s"$implicitKeyword$name: "
      case key if sym.is(Flags.Method) =>
        s"$key $name"
      case key =>
        s"$key $name: "

  /**
   * for
   * - method: method signature
   *   - e.g. `[A: Ordering](x: List[Int]): A`
   * - otherwise: its shortened type
   *   - e.g. ` java.lang.String` ` Symbols.Symbol`
   */
  def infoString(
      sym: Symbol,
      history: ShortenedNames,
      info: Type
  )(using Context): String =
    sym match
      case m if m.is(Flags.Method) =>
        defaultMethodSignature(sym, history, info)
      case _ =>
        val short = shortType(info, history)
        s"${typeString(short)}"

  /**
   * Compute method signature for the given (method) symbol.
   *
   * @return shortend name for types or the type for terms
   *         e.g. "[A: Ordering](a: A, b: B): collection.mutable.Map[A, B]"
   *              ": collection.mutable.Map[A, B]" for no-arg method
   */
  def defaultMethodSignature(
      gsym: Symbol,
      shortenedNames: ShortenedNames,
      gtpe: Type
  ): String =
    // In case rawParamss is no set, fallback to paramSymss
    val paramss =
      if gsym.rawParamss.length != 0 then gsym.rawParamss else gsym.paramSymss
    lazy val implicitParams: List[Symbol] =
      paramss.flatMap(params => params.filter(p => p.is(Flags.Implicit)))

    lazy val implicitEvidenceParams: Set[Symbol] =
      implicitParams
        .filter(p => p.name.toString.startsWith(EvidenceParamName.separator))
        .toSet

    lazy val implicitEvidencesByTypeParam: Map[Symbol, List[String]] =
      constructImplicitEvidencesByTypeParam(
        implicitEvidenceParams.toList,
        shortenedNames
      )

    val paramLabelss = paramss.flatMap { params =>
      val labels = params.flatMap { param =>
        // Don't show implicit evidence params
        // e.g.
        // from [A: Ordering](a: A, b: A)(implicit evidence$1: Ordering[A])
        // to   [A: Ordering](a: A, b: A): A
        if implicitEvidenceParams.contains(param) then Nil
        else
          paramLabel(param, implicitEvidencesByTypeParam, shortenedNames) :: Nil
      }

      // Remove empty params
      if labels.isEmpty then Nil
      else labels.iterator :: Nil
    }.iterator

    val returnType =
      typeString(shortType(gtpe.finalResultType, shortenedNames))
    methodSignature(paramLabelss, paramss, returnType)

  private def methodSignature(
      paramLabels: Iterator[Iterator[String]],
      paramss: List[List[Symbol]],
      returnType: String
  )(using Context) =
    paramLabels
      .zip(paramss)
      .map { case (params, syms) =>
        Params.paramsKind(syms) match
          case Params.Kind.TypeParameter =>
            params.mkString("[", ", ", "]")
          case Params.Kind.Normal =>
            params.mkString("(", ", ", ")")
          case Params.Kind.Using =>
            params.mkString(
              "(using ",
              ", ",
              ")"
            )
          case Params.Kind.Implicit =>
            params.mkString(
              "(implicit ",
              ", ",
              ")"
            )
      }
      .mkString("", "", s": $returnType")

  /**
   * Construct param (both value params and type params) label string (e.g. "param1: TypeOfParam", "A: Ordering")
   * for the given parameter's symbol.
   */
  private def paramLabel(
      param: Symbol,
      implicitEvidences: Map[Symbol, List[String]],
      shortenedNames: ShortenedNames
  )(using Context): String =
    val keywordName = nameString(param)
    val paramTypeString = typeString(
      shortType(param.info, shortenedNames)
    )
    if param.isTypeParam then
      // pretty context bounds
      // e.g. f[A](a: A, b: A)(implicit evidence$1: Ordering[A])
      // to   f[A: Ordering](a: A, b: A)(implicit evidence$1: Ordering[A])
      val bounds = implicitEvidences.getOrElse(param, Nil) match
        case Nil => ""
        case head :: Nil => s": $head"
        case many => many.mkString(": ", ": ", "")
      s"$keywordName$paramTypeString$bounds"
    else if param.is(Flags.Given) && param.name.toString.contains('$') then
      // For Anonymous Context Parameters
      // print only type string
      // e.g. "using Ord[T]" instead of "using x$0: Ord[T]"
      paramTypeString
    else
      val paramTypeString = typeString(
        shortType(param.info, shortenedNames)
      )
      s"$keywordName: $paramTypeString"

  /**
   * Create a mapping from type parameter symbol to its context bound string representations.
   *
   * @param implicitEvidenceParams - implicit evidence params (e.g. evidence$1: Ordering[A])
   * @return mapping from type param to its context bounds (e.g. Map(A -> List("Ordering")) )
   */
  private def constructImplicitEvidencesByTypeParam(
      implicitEvidenceParams: List[Symbol],
      shortenedNames: ShortenedNames
  ): Map[Symbol, List[String]] =
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
        buf += typeString(shortType(tycon, shortenedNames))
      }
    result.map(kv => (kv._1, kv._2.toList)).toMap
