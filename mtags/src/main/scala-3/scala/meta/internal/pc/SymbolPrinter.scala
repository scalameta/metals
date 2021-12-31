package scala.meta.internal.pc

import scala.collection.mutable.ListBuffer

import scala.meta.internal.mtags.MtagsEnrichments.*

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.NameKinds.EvidenceParamName
import dotty.tools.dotc.core.NameOps.*
import dotty.tools.dotc.core.Names.*
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.printing.RefinedPrinter
import org.eclipse.lsp4j.CompletionItemKind

class SymbolPrinter(using ctx: Context) extends RefinedPrinter(ctx):

  private val defaultWidth = 1000

  override def nameString(name: Name): String =
    super.nameString(name.stripModuleClassSuffix)

  def typeString(tpw: Type): String =
    toText(tpw).mkString(defaultWidth, false)

  def expressionTypeString(tpw: Type, history: ShortenedNames): Option[String] =
    tpw match
      case t: PolyType =>
        expressionTypeString(t.resType, history)
      case t: MethodType =>
        expressionTypeString(t.resType, history)
      case i: ImportType =>
        expressionTypeString(i.expr.typeOpt, history)
      case c: ConstantType =>
        Some(typeString(shortType(c.underlying, history)))
      case _ if !tpw.isErroneous =>
        Some(typeString(shortType(tpw, history)))
      case _ => None

  /**
   * for
   * - method: method signature
   *   - e.g. `[A: Ordering](x: List[Int]): A`
   * - otherwise: its shortened type
   *   - e.g. ` java.lang.String` ` Symbols.Symbol`
   */
  def hoverDetailString(
      sym: Symbol,
      history: ShortenedNames,
      info: Type
  )(using Context): String =
    val typeSymbol = info.typeSymbol

    def shortTypeString: String =
      val short = shortType(info, history)
      s"${typeString(short)}"

    def ownerTypeString: String =
      typeSymbol.owner.fullNameBackticked

    def name: String = nameString(sym)

    sym match
      case p if p.is(Flags.Package) =>
        s"package ${p.fullNameBackticked}"
      case c if c.is(Flags.EnumVal) =>
        s"case $name: $shortTypeString"
      // enum
      case e if e.is(Flags.Enum) || sym.companionClass.is(Flags.Enum) =>
        s"enum $name: $ownerTypeString"
      /* Type cannot be shown on the right since it is already a type
       * let's instead use that space to show the full path.
       */
      case o if typeSymbol.is(Flags.Module) => // enum
        s"${keyString(o)} $name: $ownerTypeString"
      case m if m.is(Flags.Method) =>
        defaultMethodSignature(m, history, info)
      case _ =>
        val implicitKeyword =
          if sym.is(Flags.Implicit) then List("implicit") else Nil
        val finalKeyword = if sym.is(Flags.Final) then List("final") else Nil
        val keyOrEmpty = keyString(sym)
        val keyword = if keyOrEmpty.nonEmpty then List(keyOrEmpty) else Nil
        (implicitKeyword ::: finalKeyword ::: keyword ::: (s"$name:" :: shortTypeString :: Nil))
          .mkString(" ")
    end match
  end hoverDetailString

  /**
   * Calculate the string for "detail" field in CompletionItem.
   *
   * for class or module, it's package name that it belongs to (e.g. "scala.collection" for "scala.collection.Seq")
   * otherwise, it's shortened type/method signature
   * e.g. "[A: Ordering](x: List[Int]): A", " java.lang.String"
   *
   * @param sym The symbol for completion item.
   */
  def completionDetailString(
      sym: Symbol,
      history: ShortenedNames
  ): String =
    val info = sym.info.widenTermRefExpr
    val typeSymbol = info.typeSymbol

    if sym.is(Flags.Package) || sym.isClass then " " + fullNameString(sym.owner)
    else if sym.is(Flags.Module) || typeSymbol.is(Flags.Module) then
      " " + fullNameString(typeSymbol.owner)
    else if sym.is(Flags.Method) then
      defaultMethodSignature(sym, history, info, onlyMethodParams = true)
    else
      val short = shortType(info, history)
      if short.isErroneous then "Any"
      else typeString(short)
  end completionDetailString

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
      gtpe: Type,
      onlyMethodParams: Boolean = false
  ): String =
    val (methodParams, extParams) = splitExtensionParamss(gsym)
    val paramss = methodParams ++ extParams
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

    def label(paramss: List[List[Symbol]]) = {
      paramss.flatMap { params =>
        val labels = params.flatMap { param =>
          // Don't show implicit evidence params
          // e.g.
          // from [A: Ordering](a: A, b: A)(implicit evidence$1: Ordering[A])
          // to   [A: Ordering](a: A, b: A): A
          if implicitEvidenceParams.contains(param) then Nil
          else
            paramLabel(
              param,
              implicitEvidencesByTypeParam,
              shortenedNames
            ) :: Nil
        }
        // Remove empty params
        if labels.isEmpty then Nil
        else labels.iterator :: Nil
      }
    }.iterator
    val paramLabelss = label(methodParams)
    val extLabelss = label(extParams)

    val shortenedType = shortType(gtpe.finalResultType, shortenedNames)
    val returnType =
      if shortenedType.isErroneous then "Any"
      else typeString(shortenedType)
    def extensionSignatureString =
      val extensionSignature = paramssString(extLabelss, extParams)
      if extParams.nonEmpty then
        extensionSignature.mkString("extension ", "", " ")
      else ""
    val paramssSignature = paramssString(paramLabelss, methodParams)
      .mkString("", "", s": ${returnType}")

    if onlyMethodParams then paramssSignature
    else
      extensionSignatureString +
        s"def ${gsym.name}" +
        paramssSignature
  end defaultMethodSignature

  /*
   * Check if a method is an extension method and in that case separate the parameters
   * into 2 groups to make it possible to print extensions properly.
   */
  private def splitExtensionParamss(
      gsym: Symbol
  ): (List[List[Symbol]], List[List[Symbol]]) =

    def headHasFlag(params: List[Symbol], flag: Flags.Flag): Boolean =
      params match
        case sym :: _ => sym.is(flag)
        case _ => false
    def isUsingClause(params: List[Symbol]): Boolean =
      headHasFlag(params, Flags.Given)
    def isTypeParamClause(params: List[Symbol]): Boolean =
      headHasFlag(params, Flags.TypeParam)
    def isUsingOrTypeParamClause(params: List[Symbol]): Boolean =
      isUsingClause(params) || isTypeParamClause(params)

    val paramss =
      if gsym.rawParamss.length != 0 then gsym.rawParamss else gsym.paramSymss
    if gsym.is(Flags.ExtensionMethod) then
      val filteredParams =
        if gsym.name.isRightAssocOperatorName then
          val (leadingTyParamss, rest1) = paramss.span(isTypeParamClause)
          val (leadingUsing, rest2) = rest1.span(isUsingClause)
          val (rightTyParamss, rest3) = rest2.span(isTypeParamClause)
          val (rightParamss, rest4) = rest3.splitAt(1)
          val (leftParamss, rest5) = rest4.splitAt(1)
          val (trailingUsing, rest6) = rest5.span(isUsingClause)
          if leftParamss.nonEmpty then
            leadingTyParamss ::: leadingUsing ::: leftParamss ::: rightTyParamss ::: rightParamss ::: trailingUsing ::: rest6
          else paramss // it wasn't a binary operator, after all.
        else paramss
      val trailingParamss = filteredParams
        .dropWhile(isUsingOrTypeParamClause)
        .drop(1)

      val leadingParamss =
        filteredParams.take(paramss.length - trailingParamss.length)
      (trailingParamss, leadingParamss)
    else (paramss, Nil)
    end if
  end splitExtensionParamss

  private def paramssString(
      paramLabels: Iterator[Iterator[String]],
      paramss: List[List[Symbol]]
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
      s"${keywordName}: ${paramTypeString}"
    end if
  end paramLabel

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
  end constructImplicitEvidencesByTypeParam

end SymbolPrinter
