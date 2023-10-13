package scala.meta.internal.pc

import java.nio.file.Paths

import scala.meta.internal.metals.ReportContext
import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.internal.pc.printer.MetalsPrinter
import scala.meta.internal.pc.printer.ShortenedNames
import scala.meta.pc.SymbolSearch
import scala.meta.pc.SyntheticDecoration
import scala.meta.pc.SyntheticDecorationsParams

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.Names.TermName
import dotty.tools.dotc.core.StdNames.*
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.interactive.Interactive
import dotty.tools.dotc.interactive.InteractiveDriver
import dotty.tools.dotc.util.SourceFile
import dotty.tools.dotc.util.SourcePosition
import dotty.tools.dotc.util.Spans.Span

class PcSyntheticDecorationsProvider(
    driver: InteractiveDriver,
    params: SyntheticDecorationsParams,
    symbolSearch: SymbolSearch,
)(using ReportContext):

  val uri = params.uri()
  val filePath = Paths.get(uri)
  val sourceText = params.text
  val source =
    SourceFile.virtual(filePath.toString, sourceText)
  driver.run(uri, source)
  given ctx: Context = driver.currentCtx
  val unit = driver.currentCtx.run.units.head

  def tpdTree = unit.tpdTree
  // def tpdTree = pprint.log(unit.tpdTree)

  def provide(): List[SyntheticDecoration] =
    val deepFolder = DeepFolder[Synthetics](collectDecorations)
    deepFolder(Synthetics.empty, tpdTree).decorations

  // TODO: Add adjust
  def collectDecorations(
      decorations: Synthetics,
      tree: Tree,
  ): Synthetics =
    tree match
      case ImplicitConversion(name, range) if params.implicitConversions() =>
        val adjusted = adjust(range)
        decorations
          .add(
            Decoration(
              adjusted.startPos.toLsp,
              name + "(",
              DecorationKind.ImplicitConversion,
            )
          )
          .add(
            Decoration(
              adjusted.endPos.toLsp,
              ")",
              DecorationKind.ImplicitConversion,
            )
          )
      case ImplicitParameters(names, pos, allImplicit)
          if params.implicitParameters() =>
        val label =
          if allImplicit then names.mkString("(", ", ", ")")
          else names.mkString(", ", ", ", "")
        decorations.add(
          Decoration(
            adjust(pos).toLsp,
            label,
            DecorationKind.ImplicitParameter,
          )
        )
      case TypeParameters(tpes, pos, sel)
          if params.typeParameters() && !syntheticTupleApply(sel) =>
        val label = tpes.map(toLabel(_, pos)).mkString("[", ", ", "]")
        decorations.add(
          Decoration(
            adjust(pos).endPos.toLsp,
            label,
            DecorationKind.TypeParameter,
          )
        )
      case InferredType(tpe, pos, defTree) if params.inferredTypes() =>
        val adjustedPos = adjustForInit(defTree, adjust(pos)).endPos
        if decorations.containsDef(adjustedPos.start) then decorations
        else
          decorations.add(
            Decoration(
              adjustedPos.toLsp,
              ": " + toLabel(tpe, pos),
              DecorationKind.InferredType,
            ),
            adjustedPos.start,
          )
      case _ => decorations

  private def toLabel(
      tpe: Type,
      pos: SourcePosition,
  ): String =
    val tpdPath =
      Interactive.pathTo(unit.tpdTree, pos.span)
    val indexedCtx = IndexedContext(MetalsInteractive.contextOfPath(tpdPath))
    val shortenedNames = new ShortenedNames(indexedCtx)
    val printer = MetalsPrinter.forInferredType(
      shortenedNames,
      indexedCtx,
      symbolSearch,
      includeDefaultParam = MetalsPrinter.IncludeDefaultParam.ResolveLater,
    )
    def optDealias(tpe: Type): Type =
      def isInScope(tpe: Type): Boolean =
        tpe match
          case tref: TypeRef =>
            indexedCtx.lookupSym(
              tref.currentSymbol
            ) == IndexedContext.Result.InScope
          case AppliedType(tycon, args) =>
            isInScope(tycon) && args.forall(isInScope)
          case _ => true
      if isInScope(tpe)
      then tpe
      else tpe.metalsDealias(using indexedCtx.ctx)

    val dealiased = optDealias(tpe)
    printer.tpe(dealiased)
  end toLabel

  private val definitions = IndexedContext(ctx).ctx.definitions
  private def syntheticTupleApply(tree: Tree): Boolean =
    tree match
      case sel: Select =>
        if definitions.isTupleNType(sel.symbol.info.finalResultType) then
          sel match
            case Select(tupleClass: Ident, _)
                if !tupleClass.span.isZeroExtent &&
                  tupleClass.span.exists &&
                  tupleClass.name.startsWith("Tuple") =>
              val pos = tupleClass.sourcePos
              !sourceText.slice(pos.start, pos.end).mkString.startsWith("Tuple")
            case _ => true
        else false
      case _ => false

  private def adjustForInit(defTree: Tree, pos: SourcePosition) =
    defTree match
      case dd: DefDef if dd.symbol.isConstructor =>
        if dd.rhs.isEmpty then dd
        else
          val tpeIdx = sourceText.lastIndexWhere(
            c => !c.isWhitespace && c != '=',
            dd.rhs.span.start - 1,
          )
          pos.withEnd(tpeIdx + 1)
      case _ => pos

  /**
   * @return (adjusted position, should strip backticks)
   */
  private def adjust(
      pos1: SourcePosition,
      forRename: Boolean = false,
  ): SourcePosition =
    if !pos1.span.isCorrect then pos1
    else
      val pos0 =
        val span = pos1.span
        if span.exists && span.point > span.end then
          pos1.withSpan(
            span
              .withStart(span.point)
              .withEnd(span.point + (span.end - span.start))
          )
        else pos1
      val pos =
        if pos0.end > 0 && sourceText(pos0.end - 1) == ',' then
          pos0.withEnd(pos0.end - 1)
        else pos0
      val isBackticked =
        sourceText(pos.start) == '`' &&
          pos.end > 0 &&
          sourceText(pos.end - 1) == '`'
      // when the old name contains backticks, the position is incorrect
      val isOldNameBackticked = sourceText(pos.start) != '`' &&
        pos.start > 0 &&
        sourceText(pos.start - 1) == '`' &&
        sourceText(pos.end) == '`'
      if isBackticked && forRename then
        pos.withStart(pos.start + 1).withEnd(pos.end - 1)
      else if isOldNameBackticked then
        pos.withStart(pos.start - 1).withEnd(pos.end + 1)
      else pos
  end adjust

  extension (span: Span)
    def isCorrect =
      !span.isZeroExtent && span.exists && span.start < sourceText.size && span.end <= sourceText.size

end PcSyntheticDecorationsProvider

object ImplicitConversion:
  def unapply(tree: Tree)(using Context) =
    tree match
      case Apply(fun: Ident, args) if isSynthetic(fun) =>
        val lastArgPos =
          args.lastOption.map(_.sourcePos).getOrElse(fun.sourcePos)
        Some(
          fun.symbol.decodedName,
          lastArgPos.withStart(fun.sourcePos.start),
        )
      case Apply(Select(fun, name), args)
          if name == nme.apply && isSynthetic(fun) =>
        val lastArgPos =
          args.lastOption.map(_.sourcePos).getOrElse(fun.sourcePos)
        Some(
          fun.symbol.decodedName,
          lastArgPos.withStart(fun.sourcePos.start),
        )
      case _ => None
  private def isSynthetic(tree: Tree)(using Context) =
    tree.span.isSynthetic && tree.symbol.isOneOf(Flags.GivenOrImplicit)
end ImplicitConversion

object ImplicitParameters:
  def unapply(tree: Tree)(using Context) =
    tree match
      case Apply(fun, args) if args.exists(isSyntheticArg) =>
        val (implicitArgs, providedArgs) = args.partition(isSyntheticArg)
        val allImplicit = providedArgs.isEmpty
        val pos = implicitArgs.head.sourcePos
        Some(implicitArgs.map(_.symbol.decodedName), pos, allImplicit)
      case _ => None
  private def isSyntheticArg(tree: Tree)(using Context) = tree match
    case tree: Ident =>
      tree.span.isSynthetic && tree.symbol.isOneOf(Flags.GivenOrImplicit)
    case _ => false
end ImplicitParameters

object TypeParameters:
  def unapply(tree: Tree)(using Context) =
    tree match
      case TypeApply(sel: Select, _) if isForComprehensionMethod(sel) => None
      case TypeApply(fun, args) if inferredTypeArgs(args) =>
        val tpes = args.map(_.tpe.stripTypeVar.widen.finalResultType)
        Some((tpes, tree.endPos, fun))
      case _ => None
  private def inferredTypeArgs(args: List[Tree]): Boolean =
    args.forall {
      case tt: TypeTree if tt.span.exists && !tt.span.isZeroExtent => true
      case _ => false
    }
  private val forCompMethods =
    Set(nme.map, nme.flatMap, nme.withFilter, nme.foreach)
  // We don't want to collect synthethic `map`, `withFilter`, `foreach` and `flatMap` in for-comprenhensions
  private def isForComprehensionMethod(sel: Select)(using Context): Boolean =
    val syntheticName = sel.name match
      case name: TermName => forCompMethods(name)
      case _ => false
    val wrongSpan = sel.qualifier.span.contains(sel.nameSpan)
    syntheticName && wrongSpan

end TypeParameters

object InferredType:
  def unapply(tree: Tree)(using Context) =
    tree match
      case vd @ ValDef(_, tpe, _)
          if isValidSpan(tpe.span, vd.nameSpan) &&
            !vd.symbol.is(Flags.Enum) =>
        if vd.symbol == vd.symbol.sourceSymbol then
          Some(tpe.tpe, tpe.sourcePos.withSpan(vd.nameSpan), vd)
        else None
      case vd @ DefDef(_, _, tpe, _)
          if isValidSpan(tpe.span, vd.nameSpan) &&
            tpe.span.start >= vd.nameSpan.end &&
            !vd.symbol.isConstructor &&
            !vd.symbol.is(Flags.Mutable) =>
        if vd.symbol == vd.symbol.sourceSymbol then
          Some(tpe.tpe, tpe.sourcePos, vd)
        else None
      case bd @ Bind(
            name,
            Ident(nme.WILDCARD),
          ) /* if equalSpan(bd.span, bd.nameSpan)*/ =>
        Some(bd.symbol.info, bd.namePos, bd)
      case _ => None

  private def isValidSpan(tpeSpan: Span, nameSpan: Span): Boolean =
    tpeSpan.isZeroExtent &&
      nameSpan.exists &&
      !nameSpan.isZeroExtent

end InferredType

case class Synthetics(
    decorations: List[Decoration],
    definitions: Set[Int],
):
  def containsDef(offset: Int) = definitions(offset)
  def add(decoration: Decoration, offset: Int) =
    copy(
      decorations = decoration :: decorations,
      definitions = definitions + offset,
    )
  def add(decoration: Decoration) =
    copy(decorations = decoration :: decorations)

object Synthetics:
  def empty: Synthetics = Synthetics(Nil, Set.empty)
