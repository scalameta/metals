package scala.meta.internal.pc

import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.pc.InlayHintsParams

import org.eclipse.lsp4j.InlayHint
import org.eclipse.lsp4j.InlayHintKind

final class PcInlayHintsProvider(
    protected val compiler: MetalsGlobal,
    val params: InlayHintsParams
) {
  import compiler._
  val unit: RichCompilationUnit = addCompilationUnit(
    code = params.text(),
    filename = params.uri().toString(),
    cursor = None
  )
  lazy val text = unit.source.content
  lazy val textStr = text.mkString

  typeCheck(unit)
  val pos: Position =
    unit.position(params.offset()).withEnd(params.endOffset())

  def treesInRange(): List[Tree] = {
    val tree = locateTree(pos, unit.lastBody, false)
    if (tree.isEmpty) {
      List(unit.lastBody)
    } else if (!tree.pos.isDefined || params.offset() <= tree.pos.start) {
      List(tree)
    } else enclosedChildren(tree, pos)
  }

  def provide(): List[InlayHint] =
    treesInRange()
      .flatMap(tpdTree => traverse(InlayHints.empty, tpdTree).result())

  private def adjustPos(pos: Position): Position =
    pos.adjust(text)._1

  def collectDecorations(
      tree: Tree,
      inlayHints: InlayHints
  ): InlayHints =
    tree match {
      case ImplicitConversion(symbol, range) =>
        val adjusted = adjustPos(range)
        inlayHints
          .add(
            adjusted.focusStart.toLsp,
            labelPart(symbol, symbol.decodedName) :: LabelPart("(") :: Nil,
            InlayHintKind.Parameter
          )
          .add(
            adjusted.focusEnd.toLsp,
            LabelPart(")") :: Nil,
            InlayHintKind.Parameter
          )
      case ImplicitParameters(symbols, pos, allImplicit) =>
        val labelParts = symbols.map(s => List(labelPart(s, s.decodedName)))
        val label =
          if (allImplicit) labelParts.separated("(", ", ", ")")
          else labelParts.separated(", ")
        inlayHints.add(
          adjustPos(pos).focusEnd.toLsp,
          label,
          InlayHintKind.Parameter
        )
      case ValueOf(label, pos) =>
        inlayHints.add(
          adjustPos(pos).focusEnd.toLsp,
          LabelPart("(") :: LabelPart(label) :: List(LabelPart(")")),
          InlayHintKind.Parameter
        )
      case TypeParameters(tpes, pos) if tpes.forall(_ != null) =>
        val label = tpes.map(toLabelParts(_, pos)).separated("[", ", ", "]")
        inlayHints.add(
          adjustPos(pos).focusEnd.toLsp,
          label,
          InlayHintKind.Type
        )
      case InferredType(tpe, pos) if tpe != null && !tpe.isError =>
        val adjustedPos = adjustPos(pos).focusEnd
        if (inlayHints.containsDef(adjustedPos.start)) inlayHints
        else
          inlayHints
            .add(
              adjustedPos.toLsp,
              LabelPart(": ") :: toLabelParts(tpe.finalResultType, pos),
              InlayHintKind.Type
            )
            .addDefinition(adjustedPos.start)
      case _ => inlayHints
    }

  def traverse(
      acc: InlayHints,
      tree: Tree
  ): InlayHints = {
    val inlayHints = collectDecorations(tree, acc)
    tree.children.foldLeft(inlayHints)(traverse(_, _))
  }

  private def partsFromType(
      tpe: Type,
      usedRenames: Map[Symbol, String]
  ): List[LabelPart] = {
    tpe
      .collect {
        case t: TypeRef if t.sym != NoSymbol =>
          val label = usedRenames.get(t.sym).getOrElse(t.sym.decodedName)
          labelPart(t.sym, label)
        case SingleType(_, sym) if sym != NoSymbol =>
          val label = usedRenames.get(sym).getOrElse(sym.decodedName)
          labelPart(sym, label)
      }
  }

  private def toLabelParts(
      tpe: Type,
      pos: Position
  ): List[LabelPart] = {
    val context: Context = doLocateImportContext(pos)
    val re: scala.collection.Map[Symbol, Name] = renamedSymbols(context)
    val history = new ShortenedNames(
      lookupSymbol = name =>
        context.lookupSymbol(name, sym => !sym.isStale) :: Nil,
      renames = re
    )
    val tpeStr = metalsToLongString(tpe, history)
    val usedRenames = history.getUsedRenames
    val parts = partsFromType(tpe, usedRenames)
    InlayHints.makeLabelParts(parts, tpeStr)
  }

  private def labelPart(symbol: Symbol, label: String) =
    if (symbol.pos.source == pos.source) {
      val pos = if (symbol.pos.start != symbol.pos.point) {
        symbol.pos.withStart(symbol.pos.point)
      } else {
        symbol.pos
      }
      LabelPart(label, pos = Some(pos.toLsp.getStart()))
    } else {
      LabelPart(label, symbol = semanticdbSymbol(symbol))
    }
  object ImplicitConversion {
    def unapply(tree: Tree): Option[(Symbol, Position)] =
      if (params.implicitConversions())
        tree match {
          case Apply(fun, args)
              if isImplicitConversion(fun) && args.exists(_.pos.isRange) =>
            val lastArgPos = args.lastOption.fold(fun.pos)(_.pos)
            Some((fun.symbol, lastArgPos))
          case _ => None
        }
      else None
    private def isImplicitConversion(fun: Tree) =
      fun.pos.isOffset && fun.symbol != null && fun.symbol.isImplicit
  }
  object ImplicitParameters {
    def unapply(tree: Tree): Option[(List[Symbol], Position, Boolean)] =
      if (params.implicitParameters())
        tree match {
          case Apply(_, args)
              if args.exists(isSyntheticArg) && !tree.pos.isOffset =>
            val (implicitArgs, providedArgs) = args.partition(isSyntheticArg)
            val allImplicit = providedArgs.isEmpty
            val pos = providedArgs.lastOption.fold(tree.pos)(_.pos)
            Some(
              implicitArgs.map(_.symbol),
              pos,
              allImplicit
            )
          case _ => None
        }
      else None

    private def isSyntheticArg(arg: Tree): Boolean =
      arg.pos.isOffset && arg.symbol != null && arg.symbol.isImplicit
  }

  object ValueOf {
    def unapply(tree: Tree): Option[(String, Position)] =
      if (params.implicitParameters())
        tree match {
          case Apply(ta: TypeApply, Apply(fun, _) :: _)
              if fun.pos.isOffset && isValueOf(fun.symbol) =>
            Some(
              "new " + nme.valueOf.decoded.capitalize + "(...)",
              ta.pos
            )
          case _ => None
        }
      else None
    private def isValueOf(symbol: Symbol) =
      symbol != null && symbol.safeOwner.decodedName == nme.valueOf.decoded.capitalize

  }

  object TypeParameters {
    def unapply(tree: Tree): Option[(List[Type], Position)] =
      if (params.typeParameters())
        tree match {
          case TypeApply(sel: Select, _)
              if isForComprehensionMethod(sel) || syntheticTupleApply(sel) ||
                isInfix(sel, textStr) || sel.symbol.name == nme.unapply =>
            None
          case TypeApply(fun, args)
              if args.exists(_.pos.isOffset) && tree.pos.isRange =>
            val pos = fun match {
              case sel: Select if isInfix(sel, textStr) =>
                sel.namePosition
              case _ => fun.pos
            }
            Some(args.map(_.tpe.widen), pos)
          case _ => None
        }
      else None
  }

  object InferredType {
    def unapply(tree: Tree): Option[(Type, Position)] =
      if (params.inferredTypes())
        tree match {
          case vd @ ValDef(_, _, tpt, _)
              if hasMissingTypeAnnot(vd, tpt) &&
                !primaryConstructorParam(vd.symbol) &&
                maybeShowUnapply(vd) &&
                !isCompilerGeneratedSymbol(vd.symbol) &&
                !isValDefBind(vd) =>
            Some(vd.symbol.tpe.widen, vd.namePosition)
          case dd @ DefDef(_, _, _, _, tpt, _)
              if hasMissingTypeAnnot(dd, tpt) &&
                !dd.symbol.isConstructor &&
                !dd.symbol.isMutable &&
                !samePosAsOwner(dd.symbol) =>
            Some(dd.symbol.tpe.widen, findTpePos(dd))
          case bb @ Bind(name, Ident(nme.WILDCARD))
              if params.hintsInPatternMatch && name != nme.WILDCARD && name != nme.DEFAULT_CASE =>
            Some(bb.symbol.tpe.widen, bb.namePosition)
          case _ => None
        }
      else None
    private def hasMissingTypeAnnot(tree: MemberDef, tpt: Tree) =
      tree.pos.isRange && tree.namePosition.isRange && tpt.pos.isOffset && tpt.pos.start != 0

    private def primaryConstructorParam(sym: Symbol) =
      sym.safeOwner.isPrimaryConstructor

    private def samePosAsOwner(sym: Symbol) = {
      val owner = sym.safeOwner
      sym.pos == owner.pos
    }

    private def findTpePos(dd: DefDef) = {
      if (dd.rhs.isEmpty) dd.pos
      else {
        val tpeIdx = text.lastIndexWhere(
          c => !c.isWhitespace && c != '=' && c != '{',
          dd.rhs.pos.start - 1
        )
        dd.pos.withEnd(Math.max(dd.namePosition.end, tpeIdx + 1))
      }
    }
    private def isCompilerGeneratedSymbol(sym: Symbol) =
      sym.decodedName.matches("x\\$\\d+")

    private def maybeShowUnapply(vd: ValDef) =
      (!vd.rhs.pos.isDefined && params.hintsInPatternMatch()) ||
        (isNotInUnapply(vd) || isValidUnapply(vd)) &&
        vd.rhs.pos.start > vd.namePosition.end

    private def isNotInUnapply(vd: ValDef) = vd.rhs.pos.isRange

    private def isValidUnapply(vd: ValDef) =
      params.hintsInPatternMatch() && vd.rhs.pos.isOffset

    /* If is left part of val definition bind:
     * val <<t>> @ ... =
     */
    private def isValDefBind(vd: ValDef) = {
      val afterDef = text.drop(vd.namePosition.end)
      val index = indexAfterSpacesAndComments(afterDef)
      index >= 0 && index < afterDef.size && afterDef(index) == '@'
    }
  }

  private def syntheticTupleApply(sel: Select): Boolean = {
    if (
      sel.tpe != null && compiler.definitions.isTupleType(
        sel.tpe.finalResultType
      )
    ) {
      sel match {
        case Select(tupleClass: Select, _)
            if tupleClass.pos.isRange &&
              tupleClass.name.startsWith("Tuple") =>
          val pos = tupleClass.pos
          !text.slice(pos.start, pos.end).mkString.startsWith("Tuple")
        case _ => true
      }
    } else false
  }

}
