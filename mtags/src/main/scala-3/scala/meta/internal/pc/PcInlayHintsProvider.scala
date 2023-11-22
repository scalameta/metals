package scala.meta.internal.pc

import java.nio.file.Paths

import scala.meta.internal.metals.ReportContext
import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.internal.pc.printer.MetalsPrinter
import scala.meta.internal.pc.printer.ShortenedNames
import scala.meta.pc.InlayHintsParams
import scala.meta.pc.SymbolSearch

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.StdNames.*
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.interactive.Interactive
import dotty.tools.dotc.interactive.InteractiveDriver
import dotty.tools.dotc.util.SourceFile
import dotty.tools.dotc.util.SourcePosition
import dotty.tools.dotc.util.Spans.Span
import org.eclipse.lsp4j.InlayHint
import org.eclipse.lsp4j.InlayHintKind
import org.eclipse.{lsp4j as l}

class PcInlayHintsProvider(
    driver: InteractiveDriver,
    params: InlayHintsParams,
    symbolSearch: SymbolSearch,
)(using ReportContext):

  val uri = params.uri()
  val filePath = Paths.get(uri)
  val sourceText = params.text
  val text = sourceText.toCharArray()
  val source =
    SourceFile.virtual(filePath.toString, sourceText)
  driver.run(uri, source)
  given ctx: Context = driver.currentCtx
  val unit = driver.currentCtx.run.units.head
  given InferredType.Text = InferredType.Text(text)
  val pos = driver.sourcePosition(params)

  def provide(): List[InlayHint] =
    val deepFolder = DeepFolder[InlayHints](collectDecorations)
    Interactive
      .pathTo(driver.openedTrees(params.uri()), pos)(using driver.currentCtx)
      .headOption
      .getOrElse(unit.tpdTree)
      .enclosedChildren(pos.span)
      .flatMap(tpdTree => deepFolder(InlayHints.empty, tpdTree).result())

  private def adjustPos(pos: SourcePosition): SourcePosition =
    pos.adjust(text)._1

  def collectDecorations(
      inlayHints: InlayHints,
      tree: Tree,
  ): InlayHints =
    tree match
      case ImplicitConversion(symbol, range) if params.implicitConversions() =>
        val adjusted = adjustPos(range)
        inlayHints
          .add(
            adjusted.startPos.toLsp,
            labelPart(symbol) :: LabelPart("(") :: Nil,
            InlayHintKind.Parameter,
          )
          .add(
            adjusted.endPos.toLsp,
            LabelPart(")") :: Nil,
            InlayHintKind.Parameter,
          )
      case ImplicitParameters(symbols, pos, allImplicit)
          if params.implicitParameters() =>
        val head :: rest = symbols.map(labelPart): @unchecked
        val labelParts = (head :: rest.flatMap(LabelPart(", ") :: _ :: Nil))
        val label =
          if allImplicit then
            LabelPart("(") :: labelParts ++ List(LabelPart(")"))
          else labelParts
        inlayHints.add(
          adjustPos(pos).toLsp,
          label,
          InlayHintKind.Parameter,
        )
      case ValueOf(label, pos) if params.implicitParameters() =>
        inlayHints.add(
          adjustPos(pos).toLsp,
          LabelPart("(") :: LabelPart(label) :: List(LabelPart(")")),
          InlayHintKind.Parameter,
        )
      case TypeParameters(tpes, pos, sel)
          if params.typeParameters() && !syntheticTupleApply(sel) =>
        val head :: rest = tpes.map(toLabelParts(_, pos)): @unchecked
        val label = (head :: rest.map(LabelPart(", ") :: _)).flatten
        inlayHints.add(
          adjustPos(pos).endPos.toLsp,
          LabelPart("[") :: label ++ List(LabelPart("]")),
          InlayHintKind.Type,
        )
      case InferredType(tpe, pos, defTree) if params.inferredTypes() =>
        val adjustedPos = adjustPos(pos).endPos
        if inlayHints.containsDef(adjustedPos.start) then inlayHints
        else
          inlayHints
            .add(
              adjustedPos.toLsp,
              LabelPart(": ") :: toLabelParts(tpe, pos),
              InlayHintKind.Type,
            )
            .addDefinition(adjustedPos.start)
      case _ => inlayHints

  private def toLabelParts(
      tpe: Type,
      pos: SourcePosition,
  ): List[LabelPart] =
    val tpdPath =
      Interactive.pathTo(unit.tpdTree, pos.span)
    val indexedCtx = IndexedContext(Interactive.contextOfPath(tpdPath))
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
      if isInScope(tpe) then tpe
      else tpe.metalsDealias(using indexedCtx.ctx)

    val dealiased = optDealias(tpe)
    val parts = partsFromType(dealiased)
    InlayHints.makeLabelParts(parts, printer.tpe(dealiased))
  end toLabelParts

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

  private def labelPart(symbol: Symbol) =
    if symbol.source == pos.source then
      LabelPart(
        symbol.decodedName,
        pos = Some(symbol.sourcePos.toLsp.getStart()),
      )
    else
      LabelPart(
        symbol.decodedName,
        symbol = SemanticdbSymbols.symbolName(symbol),
      )

  private def partsFromType(tpe: Type): List[LabelPart] =
    NamedPartsAccumulator(_ => true)(Nil, tpe)
      .filter(_.symbol != NoSymbol)
      .map(t => labelPart(t.symbol))
end PcInlayHintsProvider

object ImplicitConversion:
  def unapply(tree: Tree)(using Context) =
    tree match
      case Apply(fun: Ident, args) if isSynthetic(fun) =>
        implicitConversion(fun, args)
      case Apply(Select(fun, name), args)
          if name == nme.apply && isSynthetic(fun) =>
        implicitConversion(fun, args)
      case _ => None
  private def isSynthetic(tree: Tree)(using Context) =
    tree.span.isSynthetic && tree.symbol.isOneOf(Flags.GivenOrImplicit)

  private def implicitConversion(fun: Tree, args: List[Tree])(using Context) =
    val lastArgPos =
      args.lastOption.map(_.sourcePos).getOrElse(fun.sourcePos)
    Some(
      fun.symbol,
      lastArgPos.withStart(fun.sourcePos.start),
    )
end ImplicitConversion

object ImplicitParameters:
  def unapply(tree: Tree)(using Context) =
    tree match
      case Apply(fun, args)
          if args.exists(isSyntheticArg) && !tree.sourcePos.span.isZeroExtent =>
        val (implicitArgs, providedArgs) = args.partition(isSyntheticArg)
        val allImplicit = providedArgs.isEmpty
        val pos = implicitArgs.head.sourcePos
        Some(implicitArgs.map(_.symbol), pos, allImplicit)
      case _ => None

  private def isSyntheticArg(tree: Tree)(using Context) = tree match
    case tree: Ident =>
      tree.span.isSynthetic && tree.symbol.isOneOf(Flags.GivenOrImplicit)
    case _ => false
end ImplicitParameters

object ValueOf:
  def unapply(tree: Tree)(using Context) =
    tree match
      case Apply(ta @ TypeApply(fun, _), _)
          if fun.span.isSynthetic && isValueOf(fun) =>
        Some(
          "new " + tpnme.valueOf.decoded.capitalize + "(...)",
          fun.sourcePos,
        )
      case _ => None
  private def isValueOf(tree: Tree)(using Context) =
    val symbol = tree.symbol.maybeOwner
    symbol.name.decoded == tpnme.valueOf.decoded.capitalize
end ValueOf

object TypeParameters:
  def unapply(tree: Tree)(using Context) =
    tree match
      case TypeApply(sel: Select, _) if sel.isForComprehensionMethod => None
      case TypeApply(fun, args) if inferredTypeArgs(args) =>
        val pos = fun match
          case sel: Select if sel.isInfix =>
            sel.sourcePos.withEnd(sel.nameSpan.end)
          case _ => fun.sourcePos
        val tpes = args.map(_.tpe.stripTypeVar.widen.finalResultType)
        Some((tpes, pos.endPos, fun))
      case _ => None
  private def inferredTypeArgs(args: List[Tree]): Boolean =
    args.forall {
      case tt: TypeTree if tt.span.exists && !tt.span.isZeroExtent => true
      case _ => false
    }
end TypeParameters

object InferredType:
  opaque type Text = Array[Char]
  object Text:
    def apply(text: Array[Char]): Text = text

  def unapply(tree: Tree)(using text: Text, cxt: Context) =
    tree match
      case vd @ ValDef(_, tpe, _)
          if isValidSpan(tpe.span, vd.nameSpan) &&
            !vd.symbol.is(Flags.Enum) &&
            !isValDefBind(text, vd) =>
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
          ) =>
        Some(bd.symbol.info, bd.namePos, bd)
      case _ => None

  private def isValidSpan(tpeSpan: Span, nameSpan: Span): Boolean =
    tpeSpan.isZeroExtent &&
      nameSpan.exists &&
      !nameSpan.isZeroExtent

  /* If is left part of val definition bind:
   * val <<t>> @ ... =
   */
  def isValDefBind(text: Text, vd: ValDef)(using Context) =
    val afterDef = text.drop(vd.nameSpan.end)
    val index = indexAfterSpacesAndComments(afterDef)
    index >= 0 && index < afterDef.size && afterDef(index) == '@'

end InferredType
